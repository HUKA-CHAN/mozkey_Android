#ifndef MOZC_SESSION_PHRASE_HISTORY_H_
#define MOZC_SESSION_PHRASE_HISTORY_H_

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace mozc {
namespace session {

// Phrase-level input history for the Windows build.
//
// Mozc's own user-history predictor works per segment, so it cannot offer a
// whole sentence the user committed before. This store remembers
// (reading -> committed text) pairs at the moment a composition is committed
// and answers prefix queries against the reading: after committing
// いまからかえるよ -> 今から帰るよ once, typing い already offers 今から帰るよ.
// It is the Windows counterpart of the PhraseHistory store in the Android
// app layer and follows the same rules (at most 1,000 entries, at most 3
// suggestions, unconverted text is not a phrase).
//
// Persistence is a small TSV file (reading, value, use count, last-used
// time in milliseconds, with backslash escaping), shared with no other
// component. The store is intentionally free of Mozc/absl dependencies so
// its logic can be unit-tested anywhere; the caller supplies the file path.
//
// All methods are thread-safe. The process-wide instance returned by
// GetInstance() lazily loads the file on first use and rewrites it on every
// Record(); history is a convenience, so file errors are silently ignored.
class PhraseHistoryStore {
 public:
  struct Entry {
    std::string reading;
    std::string value;
    int64_t count = 0;
    int64_t last_used_millis = 0;
  };

  static constexpr int kMaxEntries = 1000;
  static constexpr int kMaxSuggestions = 3;
  // Candidate ids handed to the client for phrase-history candidates.
  // Mozc's own candidate ids are never negative in this range, and the
  // Android implementation uses the same convention with another base.
  static constexpr int32_t kCandidateIdBase = -2000000;

  // Process-wide store used by Session. The file path must be set once via
  // SetFilePath() before the first Record()/Suggest() to enable persistence;
  // without a path the store works in memory only.
  static PhraseHistoryStore* GetInstance();

  PhraseHistoryStore() = default;
  PhraseHistoryStore(const PhraseHistoryStore&) = delete;
  PhraseHistoryStore& operator=(const PhraseHistoryStore&) = delete;

  void SetFilePath(std::string path);

  // Remembers one committed phrase. Readings/values shorter than two
  // characters and unconverted text (reading == value) are ignored.
  void Record(const std::string& reading, const std::string& value);
  // Same as Record() but with an explicit timestamp, for tests.
  void RecordAt(const std::string& reading, const std::string& value,
                int64_t now_millis);

  // Phrases whose reading starts with |query|, most useful first (use
  // count, then recency), deduplicated by value. Exact-reading matches are
  // included. Returns at most |limit| entries.
  std::vector<Entry> Suggest(const std::string& query,
                             int limit = kMaxSuggestions) const;

  int Size() const;
  void Clear();

 private:
  void EnsureLoadedLocked() const;
  void SaveLocked() const;
  void TrimLocked() const;

  std::string file_path_;
  mutable std::mutex mutex_;
  // Keyed by (reading, value). Mutable because loading is lazy and happens
  // on the first const access (Suggest()/Size()) as well.
  mutable std::map<std::pair<std::string, std::string>, Entry> entries_;
  mutable bool loaded_ = false;
};

}  // namespace session
}  // namespace mozc

#endif  // MOZC_SESSION_PHRASE_HISTORY_H_
