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
      (testing "writes a post per given class and property and a classes post"
        (is (= #{"Genre.md" "referrerURL.md" "classes.md"}
               (set (map fs/file-name (fs/list-dir (fs/file dir "posts"))))))
        (is (str/includes? (slurp genre-file) "Tags: class"))
        (is (str/includes? (slurp genre-file) "<a href=\"HumanConcept.html\">HumanConcept</a>"))
        (is (str/includes? (slurp genre-file)
                           "Description: First property I've added. Could also be https://schema.org/genre\n"))
        (is (str/includes? (slurp (fs/file dir "posts" "referrerURL.md")) "Tags: property")))
      (testing "class posts have breadcrumbs from Thing with only external crumbs styled external"
        (is (str/includes? (slurp genre-file)
                           (str "<p class=\"breadcrumbs\">"
                                "<a class=\"external\" target=\"_blank\" rel=\"noopener\" href=\"https://schema.org/Thing\">Thing</a> &gt; "
                                "<a class=\"external\" target=\"_blank\" rel=\"noopener\" href=\"https://schema.org/Intangible\">Intangible</a> &gt; "
                                "<a href=\"HumanConcept.html\">HumanConcept</a> &gt; Genre</p>")))
        (is (not (str/includes? (slurp (fs/file dir "posts" "referrerURL.md")) "breadcrumbs"))))
      (testing "classes post is a preview post that counts and shows the given classes and their ancestors with only schema.org classes external and classes with subclasses collapsible"
        (let [classes (slurp (fs/file dir "posts" "classes.md"))]
          (is (str/includes? classes "Preview: true"))
          (is (str/includes? classes "This ontology has 2 schema.org classes and 2 tagaholic.me classes."))
          (is (str/includes? classes
                             (str "<ul class=\"class-hierarchy\"><li><details open><summary><a class=\"external\" href=\"https://schema.org/Thing\" rel=\"noopener\" target=\"_blank\">Thing</a></summary>"
                                  "<ul><li><details open><summary><a class=\"external\" href=\"https://schema.org/Intangible\" rel=\"noopener\" target=\"_blank\">Intangible</a></summary>"
                                  "<ul><li><details open><summary><a href=\"HumanConcept.html\">HumanConcept</a></summary>"
                                  "<ul><li><a href=\"Genre.html\">Genre</a></li></ul></details></li></ul></details></li></ul></details></li></ul>")))))
      (testing "property names link to their property in an index"
        (is (str/includes? (slurp genre-file)
                           "<strong><a class=\"external\" target=\"_blank\" rel=\"noopener\" href=\"https://schema.org/sameAs\">sameAs</a></strong>"))
        (is (str/includes? (slurp genre-file)
                           "<strong><a class=\"external\" target=\"_blank\" rel=\"noopener\" href=\"https://schema.org/description\">description</a></strong>")))
      (testing "leaves existing posts unchanged"
        (spit genre-file "edited")
        (is (str/includes? (bb-task "schema:create" "--dir" (str dir) "Genre")
                           "Skipped 2 posts"))
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
