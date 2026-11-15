(ns cldwalker.schema.publish-test
  "Runs the schema:create and schema:rebuild tasks against a temp site dir"
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- bb-task [& args]
  (:out (apply shell {:out :string} "bb" args)))

(deftest schema-create
  (fs/with-temp-dir [dir {}]
    (let [genre-file (fs/file dir "posts" "Genre.md")]
      (bb-task "schema:create" "--dir" (str dir) "Genre" "referrerURL")
      (testing "writes a post per given class and property"
        (is (= #{"Genre.md" "referrerURL.md"}
               (set (map fs/file-name (fs/list-dir (fs/file dir "posts"))))))
        (is (str/includes? (slurp genre-file) "Tags: class"))
        (is (str/includes? (slurp genre-file) "<a href=\"HumanConcept.html\">HumanConcept</a>"))
        (is (str/includes? (slurp (fs/file dir "posts" "referrerURL.md")) "Tags: property")))
      (testing "leaves existing posts unchanged"
        (spit genre-file "edited")
        (is (str/includes? (bb-task "schema:create" "--dir" (str dir) "Genre")
                           "Skipped 1 posts"))
        (is (= "edited" (slurp genre-file)))))))

(deftest schema-rebuild
  (fs/with-temp-dir [dir {}]
    (let [genre-file (fs/file dir "posts" "Genre.md")
          stale-page (fs/file dir "public" "Stale.html")]
      (fs/create-dirs (fs/parent genre-file))
      (spit genre-file "Title: Genre\nDate: 2020-01-02\nTags: class\n\nOld body\n")
      (fs/create-dirs (fs/parent stale-page))
      (spit stale-page "stale")
      (bb-task "schema:rebuild" "--dir" (str dir) "Genre")
      (testing "recreates posts and keeps their dates"
        (let [post (slurp genre-file)]
          (is (str/includes? post "Date: 2020-01-02"))
          (is (str/includes? post "## Extends"))
          (is (not (str/includes? post "Old body")))))
      (testing "renders html from a clean output dir"
        (is (str/includes? (slurp (fs/file dir "public" "Genre.html")) "HumanConcept.html"))
        (is (not (fs/exists? stale-page)))))))
