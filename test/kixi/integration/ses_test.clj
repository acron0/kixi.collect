(ns kixi.integration.ses-test
  (:require [kixi.collect.system :as sys]
            [amazonica.aws.dynamodbv2 :as ddb]
            [clj-http.client :as client]
            [clojure
             [test :refer :all]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.core.async :as async]
            [environ.core :refer [env]]
            [kixi.comms.components.kinesis :as kinesis]
            [kixi.comms.components.coreasync :as coreasync]
            [kixi.comms :as c]
            [user :as user]))

(def wait-tries (Integer/parseInt (env :wait-tries "80")))
(def wait-per-try (Integer/parseInt (env :wait-per-try "1000")))
(def run-against-staging (Boolean/parseBoolean (env :run-against-staging "false")))
(def service-url (env :service-url "localhost:8080"))
(def profile (env :system-profile "local"))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn vec-if-not
  [x]
  (if (vector? x)
    x
    (vector x)))

(defn table-exists?
  [endpoint table]
  (try
    (ddb/describe-table {:endpoint endpoint} table)
    (catch Exception e false)))

(defn delete-tables
  [endpoint table-names]
  (doseq [sub-table-names (partition-all 10 table-names)]
    (doseq [table-name sub-table-names]
      (ddb/delete-table {:endpoint endpoint} :table-name table-name))
    (loop [tables sub-table-names]
      (when (not-empty tables)
        (recur (doall (filter (partial table-exists? endpoint) tables)))))))

(defn cycle-system-fixture
  [all-tests]
  (if run-against-staging
    (user/start {} [:communications])
    (user/start {:communications (coreasync/map->CoreAsync
                                  {:profile profile})} nil))
  (try (stest/instrument)
       (all-tests)
       (finally
         (user/stop))))

(def comms (atom nil))
(def event-channel (atom nil))

(defn sink-to
  [a]
  #(do (async/>!! @a %)
       nil))

(defn extract-comms
  [all-tests]
  (reset! comms (:communications @user/system))
  (let [_ (reset! event-channel (async/chan 100))
        handler-1 (c/attach-event-with-key-handler!
                   @comms
                   :collect-integration-tests-1
                   :kixi.comms.event/id
                   (sink-to event-channel))
        handler-2 (c/attach-event-with-key-handler!
                   @comms
                   :collect-integration-tests-2
                   :kixi.event/id
                   (sink-to event-channel))]
    (try
      (all-tests)
      (finally
        (c/detach-handler! @comms handler-1)
        (c/detach-handler! @comms handler-2)
        (async/close! @event-channel)
        (reset! event-channel nil))))
  (reset! comms nil))


(defn event-for
  [uid event]
  (or (= uid
         (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id]))
      (= uid
         (get-in event [:kixi/user :kixi.user/id]))))

(defn wait-for-events
  [uid & event-types]
  (let [event-types (set event-types)]
    (first
     (async/alts!!
      (mapv (fn [c]
              (async/go-loop
                  [event (async/<! c)]
                (if (and (event-for uid event)
                         (or (event-types (:kixi.comms.event/key event))
                             (event-types (:kixi.event/type event))))
                  event
                  (when event
                    (recur (async/<! c))))))
            [@event-channel
             (async/timeout (* wait-tries
                               wait-per-try))])))))

(defn send-mail-cmd
  ([uid mail]
   (send-mail-cmd uid uid mail))
  ([uid ugroup mail]
   (c/send-command!
    @comms
    :kixi.collect/send-mail
    "1.0.0"
    {:kixi.user/id uid
     :kixi.user/groups (vec-if-not ugroup)}
    mail
    {:kixi.comms.command/partition-key uid})))

(defn send-group-mail-cmd
  ([uid mail]
   (send-mail-cmd uid uid mail))
  ([uid ugroup mail]
   (c/send-valid-command!
    @comms
    (merge
     {:kixi.command/type :kixi.collect/send-group-mail
      :kixi.command/version "1.0.0"
      :kixi/user {:kixi.user/id uid
                  :kixi.user/groups (vec-if-not ugroup)}}
     mail)
    {:partition-key uid})))

(defn send-mail
  ([uid mail]
   (send-mail uid uid mail))
  ([uid ugroup mail]
   (send-mail-cmd uid ugroup mail)
   (wait-for-events uid :kixi.collect/mail-rejected :kixi.collect/mail-accepted)))

(defn send-group-mail
  ([uid mail]
   (send-group-mail uid uid mail))
  ([uid ugroup mail]
   (send-group-mail-cmd uid ugroup mail)
   (wait-for-events uid :kixi.collect/group-mail-rejected :kixi.collect/group-mail-accepted)))


(use-fixtures :once cycle-system-fixture extract-comms)

(deftest healthcheck-check
  (let [hc-resp (client/get (str "http://" service-url "/healthcheck"))]
    (is (= (:status hc-resp)
           200))))

(def test-mail {:destination {:to-addresses ["developers@mastodonc.com"]}
                :source "support@mastodonc.com"
                :message {:subject (str "kixi.collect - " profile " - Integration Test Mail")
                          :body {:text "<<&env.default_header>>This is an email from the integration tests for kixi.collect.<<&env.default_footer>>"}}})

(def test-group-mail
  {:kixi.collect/destination {:kixi.collect.destination/to-groups #{"c645d47d-1236-4dda-a16f-2d33941b5993" ;; AW
                                                                  "0ace7b64-4f2a-4665-8784-b44ff7be63db" ;; 'The Toms'
                                                                  }}
   :kixi.collect/source "support@mastodonc.com"
   :kixi.collect/message {:kixi.collect.message/subject (str "kixi.collect - " profile " - Integration Test Mail #2")
                         :kixi.collect.message/body {:kixi.collect.message/text
                                                    "<<&env.default_header>>This is an email from the integration tests for kixi.collect.<<&env.default_footer>>"}}})

(deftest send-acceptable-mail
  (let [uid (uuid)
        event (send-mail uid test-mail)]
    (is (= :kixi.collect/mail-accepted
           (:kixi.comms.event/key event)) (pr-str event))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))

(deftest send-unacceptable-mail
  (let [uid (uuid)
        event (send-mail uid {:mail ""})]
    (is (= :kixi.collect/mail-rejected
           (:kixi.comms.event/key event)))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))

(deftest send-acceptable-group-mail
  (let [uid (uuid)
        event (send-group-mail uid test-group-mail)]
    (is (= :kixi.collect/group-mail-accepted
           (:kixi.event/type event)))
    (is (= uid
           (get-in event [:kixi/user :kixi.user/id])))))

(comment
  "We can't test this because `send-valid-event!` boots us out"
  (deftest send-unacceptable-group-mail
    (let [uid (uuid)
          event (send-group-mail uid (dissoc test-group-mail :kixi.collect/destination))]
      (is (= :kixi.collect/group-mail-rejected
             (:kixi.event/type event)))
      (is (= uid
             (get-in event [:kixi/user :kixi.user/id]))))))