(ns akvo.flow.maps.boundary.master-db-test
  (:require
    [akvo.flow.maps.boundary.master-db :as master-db]
    [clojure.test :refer :all]))

(deftest db-name
  (are
    [topic-name expected-name]
    (= expected-name (master-db/db-name-for-tenant topic-name))

    "foo" "foo"
    "foo.bar" "foo_bar"
    "foo bar" "foo_bar"
    "foo$bar" "foo_bar"
    "foo-bar" "foo_bar"
    "foo_bar" "foo_bar"
    "FOO" "foo"))
