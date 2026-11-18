#include "session/phrase_history.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace mozc {
namespace session {
namespace {

// Counts Unicode code points in a UTF-8 string. Invalid bytes count as one
// character each; history text is produced by Mozc itself, so this only
// needs to be a sane approximation of the character count.
size_t Utf8Length(const std::string& text) {
  size_t length = 0;
  for (const unsigned char c : text) {
    if ((c & 0xC0) != 0x80) {
      ++length;
    }
  }
  return length;
}

int64_t NowMillis() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::system_clock::now().time_since_epoch())
      .count();
}

std::string Escape(const std::string& text) {
  std::string out;
  out.reserve(text.size());
  for (const char c : text) {
    switch (c) {
      case '\\':
        out += "\\\\";
        break;
      case '\t':
        out += "\\t";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      default:
        out += c;
        break;
    }
  }
  return out;
}

std::string Unescape(const std::string& text) {
  if (text.find('\\') == std::string::npos) {
    return text;
  }
  std::string out;
  out.reserve(text.size());
  for (size_t i = 0; i < text.size();) {
    const char c = text[i];
    if (c == '\\' && i + 1 < text.size()) {
      switch (text[i + 1]) {
        case 't':
          out += '\t';
          break;
        case 'n':
          out += '\n';
          break;
        case 'r':
          out += '\r';
          break;
        case '\\':
          out += '\\';
          break;
        default:
          out += text[i + 1];
          break;
      }
      i += 2;
    } else {
      out += c;
      ++i;
    }
  }
  return out;
}

bool ParseInt64(const std::string& text, int64_t* out) {
  if (text.empty()) {
    return false;
  }
  try {
    size_t consumed = 0;
    const long long value = std::stoll(text, &consumed);
    if (consumed != text.size()) {
      return false;
    }
    *out = value;
    return true;
  } catch (...) {
    return false;
  }
}

}  // namespace

// static
PhraseHistoryStore* PhraseHistoryStore::GetInstance() {
  static PhraseHistoryStore instance;
  return &instance;
}

void PhraseHistoryStore::SetFilePath(std::string path) {
  std::lock_guard<std::mutex> lock(mutex_);
  file_path_ = std::move(path);
  loaded_ = false;
  entries_.clear();
}

void PhraseHistoryStore::Record(const std::string& reading,
                                const std::string& value) {
  RecordAt(reading, value, NowMillis());
}

void PhraseHistoryStore::RecordAt(const std::string& reading,
                                  const std::string& value,
                                  const int64_t now_millis) {
  if (Utf8Length(reading) < 2 || Utf8Length(value) < 2) {
    return;
  }
  if (reading == value) {
    return;
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    EnsureLoadedLocked();
    const auto key = std::make_pair(reading, value);
    auto it = entries_.find(key);
    if (it == entries_.end()) {
      entries_.emplace(key, Entry{reading, value, 1, now_millis});
    } else {
      it->second.count += 1;
      it->second.last_used_millis = now_millis;
    }
    TrimLocked();
  }
  std::lock_guard<std::mutex> lock(mutex_);
  SaveLocked();
}

std::vector<PhraseHistoryStore::Entry> PhraseHistoryStore::Suggest(
    const std::string& query, const int limit) const {
  if (query.empty() || limit <= 0) {
    return {};
  }
  std::lock_guard<std::mutex> lock(mutex_);
  EnsureLoadedLocked();
  std::vector<Entry> matches;
  for (const auto& [key, entry] : entries_) {
    if (entry.reading.size() >= query.size() &&
        entry.reading.compare(0, query.size(), query) == 0 &&
        entry.value != query) {
      matches.push_back(entry);
    }
  }
  std::sort(matches.begin(), matches.end(),
            [](const Entry& lhs, const Entry& rhs) {
              if (lhs.count != rhs.count) {
                return lhs.count > rhs.count;
              }
              return lhs.last_used_millis > rhs.last_used_millis;
            });
  std::vector<Entry> result;
  for (const Entry& entry : matches) {
    bool duplicate = false;
    for (const Entry& chosen : result) {
      if (chosen.value == entry.value) {
        duplicate = true;
        break;
      }
    }
    if (duplicate) {
      continue;
    }
    result.push_back(entry);
    if (static_cast<int>(result.size()) >= limit) {
      break;
    }
  }
  return result;
}

int PhraseHistoryStore::Size() const {
  std::lock_guard<std::mutex> lock(mutex_);
  EnsureLoadedLocked();
  return static_cast<int>(entries_.size());
}

void PhraseHistoryStore::Clear() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    EnsureLoadedLocked();
    entries_.clear();
  }
  std::lock_guard<std::mutex> lock(mutex_);
  SaveLocked();
}

void PhraseHistoryStore::EnsureLoadedLocked() const {
  if (loaded_) {
    return;
  }
  loaded_ = true;
  if (file_path_.empty()) {
    return;
  }
  std::ifstream in(file_path_, std::ios::binary);
  if (!in) {
    return;
  }
  std::string line;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    // Split into exactly 4 tab-separated fields.
    std::vector<std::string> parts;
    size_t start = 0;
    while (true) {
      const size_t tab = line.find('\t', start);
      if (tab == std::string::npos) {
        parts.push_back(line.substr(start));
        break;
      }
      parts.push_back(line.substr(start, tab - start));
      start = tab + 1;
    }
    if (parts.size() != 4) {
      continue;
    }
    const std::string reading = Unescape(parts[0]);
    const std::string value = Unescape(parts[1]);
    int64_t count = 0;
    int64_t last_used = 0;
    if (!ParseInt64(parts[2], &count) || !ParseInt64(parts[3], &last_used)) {
      continue;
    }
    if (reading.empty() || value.empty()) {
      continue;
    }
    entries_.emplace(std::make_pair(reading, value),
                     Entry{reading, value, count, last_used});
  }
  TrimLocked();
}

void PhraseHistoryStore::SaveLocked() const {
  if (file_path_.empty()) {
    return;
  }
  // Write to a temporary file and rename, so a crash mid-write cannot
  // leave a truncated history behind.
  const std::string tmp_path = file_path_ + ".tmp";
  {
    std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
    if (!out) {
      return;
    }
    for (const auto& [key, entry] : entries_) {
      out << Escape(entry.reading) << '\t' << Escape(entry.value) << '\t'
          << entry.count << '\t' << entry.last_used_millis << '\n';
    }
    if (!out) {
      std::remove(tmp_path.c_str());
      return;
    }
  }
  if (std::rename(tmp_path.c_str(), file_path_.c_str()) != 0) {
    std::remove(tmp_path.c_str());
    // Fall back to a direct write (e.g. rename across filesystems).
    std::ofstream out(file_path_, std::ios::binary | std::ios::trunc);
    if (!out) {
      return;
    }
    for (const auto& [key, entry] : entries_) {
      out << Escape(entry.reading) << '\t' << Escape(entry.value) << '\t'
          << entry.count << '\t' << entry.last_used_millis << '\n';
    }
  }
}

void PhraseHistoryStore::TrimLocked() const {
  const int excess = static_cast<int>(entries_.size()) - kMaxEntries;
  if (excess <= 0) {
    return;
  }
  std::vector<Entry> weakest;
  weakest.reserve(entries_.size());
  for (const auto& [key, entry] : entries_) {
    weakest.push_back(entry);
  }
  std::sort(weakest.begin(), weakest.end(),
            [](const Entry& lhs, const Entry& rhs) {
              if (lhs.count != rhs.count) {
                return lhs.count < rhs.count;
              }
              return lhs.last_used_millis < rhs.last_used_millis;
            });
  for (int i = 0; i < excess; ++i) {
    entries_.erase(
        std::make_pair(weakest[i].reading, weakest[i].value));
  }
}

}  // namespace session
}  // namespace mozc
