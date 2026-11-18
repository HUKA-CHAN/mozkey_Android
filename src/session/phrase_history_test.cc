#include "session/phrase_history.h"

#include <cstdio>
#include <string>
#include <vector>

#include "testing/gunit.h"

namespace mozc::session {
namespace {

std::string TestFilePath(const std::string& name) {
  return testing::TempDir() + "/" + name;
}

std::vector<std::string> Values(
    const std::vector<PhraseHistoryStore::Entry>& entries) {
  std::vector<std::string> values;
  for (const auto& entry : entries) {
    values.push_back(entry.value);
  }
  return values;
}

TEST(PhraseHistoryTest, SuggestsCommittedPhraseByReadingPrefix) {
  PhraseHistoryStore store;
  store.RecordAt("いまからかえるよ", "今から帰るよ", 1000);
  // The motivating example: typing just い offers the whole phrase.
  ASSERT_EQ(store.Suggest("い").size(), 1);
  EXPECT_EQ(store.Suggest("い")[0].value, "今から帰るよ");
  EXPECT_EQ(store.Suggest("いま").size(), 1);
  EXPECT_EQ(store.Suggest("いまからかえるよ").size(), 1);
  // Non-prefix queries and empty queries match nothing.
  EXPECT_TRUE(store.Suggest("かえる").empty());
  EXPECT_TRUE(store.Suggest("").empty());
}

TEST(PhraseHistoryTest, IgnoresShortAndUnconvertedPhrases) {
  PhraseHistoryStore store;
  store.RecordAt("あ", "亜", 1000);
  store.RecordAt("あした", "あした", 1000);
  EXPECT_EQ(store.Size(), 0);
}

TEST(PhraseHistoryTest, RanksByCountThenRecency) {
  PhraseHistoryStore store;
  store.RecordAt("あしたは", "明日は", 3000);
  store.RecordAt("あしたは", "明日は", 4000);
  store.RecordAt("あしたの", "明日の", 5000);
  EXPECT_EQ(Values(store.Suggest("あした")),
            std::vector<std::string>({"明日は", "明日の"}));
  // Equal counts: the more recently used phrase wins.
  store.RecordAt("あしたの", "明日の", 6000);
  EXPECT_EQ(Values(store.Suggest("あした")),
            std::vector<std::string>({"明日の", "明日は"}));
}

TEST(PhraseHistoryTest, CapsSuggestionsAndDeduplicatesByValue) {
  PhraseHistoryStore store;
  store.RecordAt("あしたは", "明日は", 1000);
  store.RecordAt("あしたの", "明日の", 2000);
  store.RecordAt("あしたに", "明日に", 3000);
  store.RecordAt("あしたx", "同じ", 4000);
  store.RecordAt("あしたy", "同じ", 5000);
  const auto suggestions = store.Suggest("あした");
  ASSERT_EQ(suggestions.size(), PhraseHistoryStore::kMaxSuggestions);
  int same = 0;
  for (const std::string& value : Values(suggestions)) {
    if (value == "同じ") {
      ++same;
    }
  }
  EXPECT_LE(same, 1);
}

TEST(PhraseHistoryTest, DoesNotSuggestValueEqualToQuery) {
  PhraseHistoryStore store;
  store.RecordAt("テスト", "テストです", 1000);
  EXPECT_TRUE(store.Suggest("テストです").empty());
}

TEST(PhraseHistoryTest, PersistsAcrossInstances) {
  const std::string path = TestFilePath("phrase_history_test.tsv");
  std::remove(path.c_str());
  {
    PhraseHistoryStore store;
    store.SetFilePath(path);
    store.RecordAt("いまからかえるよ", "今から帰るよ", 1000);
    store.RecordAt("あしたは", "明日は", 2000);
    store.RecordAt("あしたは", "明日は", 3000);
  }
  PhraseHistoryStore store;
  store.SetFilePath(path);
  ASSERT_EQ(store.Suggest("い").size(), 1);
  EXPECT_EQ(store.Suggest("い")[0].value, "今から帰るよ");
  ASSERT_EQ(store.Suggest("あした").size(), 1);
  EXPECT_EQ(store.Suggest("あした")[0].count, 2);
  std::remove(path.c_str());
}

TEST(PhraseHistoryTest, EscapingRoundTrip) {
  const std::string path = TestFilePath("phrase_history_escape_test.tsv");
  std::remove(path.c_str());
  {
    PhraseHistoryStore store;
    store.SetFilePath(path);
    store.RecordAt("よみかた", "値\t改\n行\\", 1000);
  }
  PhraseHistoryStore store;
  store.SetFilePath(path);
  ASSERT_EQ(store.Suggest("よみ").size(), 1);
  EXPECT_EQ(store.Suggest("よみ")[0].value, "値\t改\n行\\");
  std::remove(path.c_str());
}

TEST(PhraseHistoryTest, TrimsWeakestEntriesAtCapacity) {
  PhraseHistoryStore store;
  for (int i = 0; i < PhraseHistoryStore::kMaxEntries + 5; ++i) {
    store.RecordAt("よみ" + std::to_string(i), "値" + std::to_string(i),
                   1000 + i);
  }
  EXPECT_EQ(store.Size(), PhraseHistoryStore::kMaxEntries);
  // The oldest, least used entries are dropped first.
  EXPECT_TRUE(store.Suggest("よみ0").empty() ||
              store.Suggest("よみ0")[0].value != "値0");
}

}  // namespace
}  // namespace mozc::session
