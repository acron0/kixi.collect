(ns kixi.collect-test
  (:require [amazonica.aws.simpleemail :as email]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [kixi.collect.ses :as m]
            [kixi.comms.time :as ct]))

(def example-payload {:destination {:to-addresses ["example@example.com"]}
                      :source "no-reply@example.com"
                      :message {:subject "Test Subject"
                                :body {:html "testing 1-2-3-4"
                                       :text "testing 1-2-3-4"}}})

(deftest valid-payload-passes
  (is (nil? (s/explain-data ::m/payload example-payload))))

(def test-text "This is an email from the integration tests for kixi.collect.")

(def test-body {:body {:text (str "<<&env.default_header>>" test-text "<<&env.default_footer>>")
                       :html (str "<<&env.default_header>>" test-text "<<&env.default_footer>>")}})

(deftest templating-text
  (is (= {:body {:text (str (slurp (io/resource "emails/default-text-header.txt")) test-text (slurp (io/resource "emails/default-text-footer.txt")))
                 :html (str (slurp (io/resource "emails/default-html-header.html")) test-text (slurp (io/resource "emails/default-html-footer.html")))}}
         (m/render-templates (m/merge-in-render-vars "rendered-base-url") test-body))))

(st/instrument)

(deftest mail-sender
  (with-redefs [email/send-email (constantly :redefed-accept)]
    (let [sender (m/create-mail-sender "endpoint" "base-url")]
      (is (= (m/accepted {} :redefed-accept)
             (sender {:kixi.comms.command/payload example-payload}))))))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(def new-example-payload {:kixi.message/type :command
                          :kixi.command/type :kixi.collect/send-group-mail
                          :kixi.command/version "1.0.0"
                          :kixi.command/id (uuid)
                          :kixi.command/created-at (ct/timestamp)
                          :kixi/user {:kixi.user/id (uuid)
                                      :kixi.user/groups #{(uuid)}}
                          :kixi.collect/destination {:kixi.collect.destination/to-groups [(str (java.util.UUID/randomUUID))]}
                          :kixi.collect/source "no-reply@example.com"
                          :kixi.collect/message {:kixi.collect.message/subject "Test Subject"
                                                :kixi.collect.message/body {:kixi.collect.message/html "testing 1-2-3-4"
                                                                           :kixi.collect.message/text "testing 1-2-3-4"}}})

(deftest group-mail-sender
  (with-redefs [email/send-email (constantly :redefed-accept)
                kixi.collect.heimdall/resolve-group-emails (fn [_ _ g] #{"developers@mastodonc.com"})]
    (let [sender (m/create-group-mail-sender {} "endpoint" "base-url")]
      (is (= (m/group-accepted new-example-payload)
             (sender new-example-payload)))
      (is (= (:kixi.collect/destination new-example-payload)
             (:kixi.collect/destination (first (sender new-example-payload)))
             (:kixi.collect/destination (first (m/group-accepted new-example-payload))))))))
