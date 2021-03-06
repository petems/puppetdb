(ns puppetlabs.puppetdb.cli.benchmark-test
  (:require [puppetlabs.puppetdb.cli.benchmark :as benchmark]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.cli.export :as cli-export]
            [puppetlabs.puppetdb.testutils.cli :refer [get-nodes example-catalog
                                                       example-report example-facts
                                                       example-certname]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.test]
            [clojure.core.async :refer [<!! timeout close!]]))


(defn mock-submit-record-fn [submitted-records entity]
  (fn [base-url certname version payload-string]
    (swap! submitted-records conj
           {:entity entity
            :base-url base-url
            :version version
            :payload-string payload-string
            :payload (clojure.walk/keywordize-keys payload-string)})))

(defn call-with-benchmark-status
  [config cli-args f]
  (let [submitted-records (atom [])]
    (with-redefs [client/submit-catalog (mock-submit-record-fn submitted-records
                                                               :catalog)
                  client/submit-report (mock-submit-record-fn submitted-records
                                                              :report)
                  client/submit-facts (mock-submit-record-fn submitted-records
                                                             :factset)
                  config/load-config (fn [_] config)
                  ;; This normally calls System/exit on a cli error;
                  ;; we'd rather have the exception.
                  utils/try+-process-cli! (fn [body] (body))
                  benchmark/benchmark-shutdown-timeout tu/default-timeout-ms]
      (f submitted-records (benchmark/benchmark cli-args)))))

(defn benchmark-nummsgs
  [config & cli-args]
  ;; Assumes cli-args does not indicate a --runinterval) run
  (call-with-benchmark-status config cli-args
                              (fn [submitted {:keys [join]}]
                                (is (= true (join tu/default-timeout-ms)))
                                @submitted)))

(deftest config-is-required
  (is (thrown+-with-msg?
       [:kind ::ks/cli-error]
       #"Missing required argument '--config'"
       (benchmark-nummsgs {}))))

(deftest numhosts-is-required
  (is (thrown+-with-msg?
       [:kind ::ks/cli-error]
       #"Missing required argument '--numhosts'"
       (benchmark-nummsgs {}
                          "--config" "anything.ini"))))

(deftest nummsgs-or-runinterval-is-required
  (is (thrown+-with-msg?
       [:kind ::utils/cli-error]
       #"Either -N/--nummsgs or -i/--runinterval is required."
       (benchmark-nummsgs {}
                          "--config" "anything.ini"
                          "--numhosts" "42"))))

(deftest runs-with-runinterval
  (call-with-benchmark-status
   {}
   ["--config" "anything.ini" "--numhosts" "333" "--runinterval" "1"]
   (fn [submitted {:keys [stop]}]
     (let [enough-records (* 3 42)
           finished (promise)
           watch-key (Object.)
           watcher (fn [k ref old new]
                     (when (>= (count new) enough-records)
                       (deliver finished true)))]
       (add-watch submitted watch-key watcher)
       (when-not (>= (count @submitted) enough-records) ; avoid add-watch race
         (deref finished tu/default-timeout-ms nil))
       (is (>= (count @submitted) enough-records))
       (stop)))))

(deftest multiple-messages-and-hosts
  (let [numhosts 2
        nummsgs 3
        submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" (str numhosts)
                                     "--nummsgs" (str nummsgs))]
    (is (= (* numhosts nummsgs 3) (count submitted)))))

(deftest archive-flag-works
  (let [export-out-file (.getPath (tu/temp-file "benchmark-test" ".tar.gz"))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace catalog" 8 example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "store report" 7 example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace facts" 4 example-facts)
       (#'cli-export/-main "--outfile" export-out-file
                           "--host" (:host svc-utils/*base-url*)
                           "--port" (str (:port svc-utils/*base-url*)))))
    (let [numhosts 2
          nummsgs 3
          submitted (benchmark-nummsgs {}
                                       "--config" "anything.ini"
                                       "--numhosts" (str numhosts)
                                       "--nummsgs" (str nummsgs)
                                       "--archive" export-out-file)]
      (is (= (* numhosts nummsgs 3) (count submitted))))))

(deftest consecutive-reports-are-distinct
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10")
        reports (->> submitted
                     (filter #(= :report (:entity %)))
                     (map :payload))]
    (is (= 10 (->> reports (map :configuration_version) distinct count)))
    (is (= 10 (->> reports (map :start_time) distinct count)))
    (is (= 10 (->> reports (map :end_time) distinct count)))))

(deftest randomize-catalogs-and-factsets
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10"
                                     "--rand-perc" "100")
        catalog-hashes (->> submitted
                            (filter #(= :catalog (:entity %)))
                            (map :payload)
                            (map hash))
        factset-hashes (->> submitted
                            (filter #(= :factset (:entity %)))
                            (map :payload)
                            (map hash))]
    (is (= 10 (count (distinct catalog-hashes))))
    (is (= 10 (count (distinct factset-hashes))))))
