(ns ^{:doc "Integration test for metadata db service"}
  cmr.transmit.metadata-db-test
  (:require [clojure.test :refer :all]
            [cmr.transmit.metadata-db :as svc]))

(deftest metadata-db-unavailable-test
  (with-redefs [svc/endpoint (fn [] {:host "localhost" :port "777"})]
    (try
      (svc/get-concept {} "C1234-CMR_PROV1" 0)
      (catch java.net.ConnectException e
        (is (= "Connection refused" (.getMessage e)))))))
