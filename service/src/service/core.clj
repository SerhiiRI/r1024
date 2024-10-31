(ns service.core
  (:require
   [clojure.core.async :as async]
   [clojure.pprint]
   [clojure.set]
   [clojure.string]
   [compojure.core :refer :all]
   [compojure.handler]
   [compojure.route]
   [malli.core :as m]
   [org.httpkit.server]
   [ring.middleware.defaults :as ring-middleware]
   [ring.middleware.json :as ring-middleware-json]
   [ring.middleware.params])
  (:import
   [org.apache.kafka.clients.consumer KafkaConsumer
    ConsumerConfig ConsumerRecord ConsumerRecords]))

;; ███████ ████████  █████  ████████ ███████ 
;; ██         ██    ██   ██    ██    ██      
;; ███████    ██    ███████    ██    █████   
;;      ██    ██    ██   ██    ██    ██      
;; ███████    ██    ██   ██    ██    ███████
;; =========================================
;; Додаток поділений на частини.
;; Й перша з них це керування стейтом
;; застосунку, що має простий функційни
;; спосіб роботи з одним Атомом стейту.

;; Initial state part

(defn ^:private initial-state []
  {;; Генератор унікального значення
   ;; для фільтра.
   :filters-id-count -1
   ;; Зберігає наші фільтри
   ;;  [{:id 0 :topic "AAA", :q "12" :tx 1730396552679} {...} ]
   :filters []
   ;; Зберігає дані з топіка. Ключ - топік
   ;;  {"books" [{:value "VALUE1" :tx 1730396552679} {...} ...]
   ;;   ... }
   :topic {}})

;; Чому Атом?
;; - однією з умов є робота з конкурентними запитами
;;   тож я просто використовую атома й скидую проблему
;;   на СТМ, оскільки задача не вимагала контролю над 
;;   транзакційною логікою.
(defonce application-storage
  (atom (initial-state)))

(defn app:state:reset []
  (reset! application-storage (initial-state)))

;; -----------
;; Filter Data

;; Хандлер що вносить новий фільтер до стейту.
;; Проблеми?
;;  - оскільки ці функції мають процедуральний нечистий характер
;;    можна було органзувати біль чистий редюсер, і саму процедуру
;;    як врапер над чистою функою
;;  - m/validate краще б був асертом.
;;  - Помір часу в System/currentTimeMillis. Можна було тримати в #inst
(defn app:filter:add [filter-m]
  (when (m/validate
          [:map
           [:topic [:string {:min 3}]]
           [:q [:string {:min 1}]]]
          filter-m)
    (let [{filters :filters
           filters-id-count :filters-id-count} @application-storage]
      (if-let [already-existing-id (some->> filters
                                     (filter (fn [f] (= filter-m (select-keys f [:topic :q]))))
                                     (not-empty)
                                     (first)
                                     :id)]
        already-existing-id
        (let [new-filters-id-count (inc filters-id-count)
              ;; Трансформуємо мапку
              ;;  {:topic "AAA", :q "12"}
              ;; до структури й сторимо в ДБ
              ;;  {:id 0 :topic "AAA", :q "12" :tx 1730396552679} 
              new-filter-m
              (assoc filter-m
                :id new-filters-id-count
                :tx (System/currentTimeMillis))]
          (println "State handler, add filter: " new-filter-m)
          (swap! application-storage
            (fn [state]
              (-> state
                (assoc :filters-id-count new-filters-id-count)
                (update :filters conj new-filter-m))))
          new-filters-id-count)))))

;; Весь рендер по curl вертався 
;; дякуючи табличному претті прінтингу
(defn app:filter:ascii-table []
  (if (empty? (:filters @application-storage))
    "- empty filter list -"
    (with-out-str
      (clojure.pprint/print-table [:id :topic :q :tx]
        (:filters @application-storage)))))

(defn app:filter:print-table []
  (print "============== Print Filter Table ==============")
  (println (app:filter:ascii-table)))

;; тут також краще б виглядав Процедура з асертом
;; а всередині чистий хендлер на мутовання стейту.
(defn app:filter:remove [id]
  (when (m/validate pos-int? 12)
    (swap! application-storage
      (fn [state]
        (update state :filters (fn [all-filters] (vec (remove (fn [f] (= id (:id f))) all-filters))))))
    true))

;; ----------
;; Kafka Data

;; Функція хендлер що витягує дані з КафкаКоннектора
(defn app:kafka:handle-data [topic value]
  (when (and
          (m/validate [:string {:min 3}] topic)
          (m/validate [:string {:min 1}] value))
    (println (format "kafka handler [%s], value: '%s'" topic value))
    (swap! application-storage
      (fn [state]
        (-> state
          (update-in [:topic topic] (fnil conj []) {:value value
                                                    :tx (System/currentTimeMillis)}))))
    true))

;; Функція що збирає логіку роботи
;;   /filters
;;   /filters?id=N
;; Запитів. 
(defn app:kafka:data:ascii-table [& {:keys [filter-id]}]
  (with-out-str
    (let [topic  (:topic   @application-storage)
          filters (:filters @application-storage)
          ;; time-edge - це значення ДО котрого
          ;; ми хочемо бачити наші результати, крайнє
          ;; значення по фільтрам та даним
          time-edge
          (:tx
           (first
             (filter (fn [f] (= filter-id (:id f)))
               filters)))
          ;; time-edge-filters - список агрегатних фільтрів
          ;; по котрим й буде власне працювати пошук. Умовна
          ;; .. AND ... конструкція
          time-edge-filters
          (when time-edge
            (->> filters
              (filter (fn [{:keys [tx]}] (<= tx time-edge)))
              (mapv   (fn [filter-m] (update filter-m :q clojure.string/lower-case)))
              (not-empty)))
          _
          (when time-edge-filters
            ;; Швидше виписування фільтрів що будуть застосовуватися
            ;; на датасеті з отриманих топіком повідомлень.
            (do
              (print "Active Filters")
              (clojure.pprint/print-table [:id :topic :q :tx] time-edge-filters)
              (println)))]
      
      (if time-edge
        (print "Filtered data")
        (print "Unfiltered data"))

      (clojure.pprint/print-table [:index :topic :value :tx]
        (cond->> topic

          ;; 1) витягуємо дані з УСІХ топіків.
          ;;    оскільки фільтри можуть стосуватися не тільки
          ;;    топіка букс й повинні процеситися в одній петлі
          true
          (reduce
            (fn [acc [topic-name topic-values]]
              (into acc (mapv (fn [v] (merge {:topic topic-name} v)) topic-values)))
            [])

          ;; 2) Якщо був вибраний фільтр то для початку
          ;;    зрізаємо всі повідомлення по часу
          time-edge
          (filter
            (fn [{:keys [tx]}]
              (<= tx time-edge)))

          ;; 3) Використання композитного списку фіьлтрів обменежного по часу вибраним
          ;;    на обмеженому по часу ивбраним фільтром списком повідомлень
          time-edge-filters
          (filter
            (fn [{:keys [value]} ]
              (reduce (fn [acc-bool {:keys [q]}]
                        (if (clojure.string/includes? (clojure.string/lower-case value) q)
                          (reduced true)
                          false))
                false
                time-edge-filters)))

          ;; 4) додаємо індекси шоб було красіво.
          true
          (map-indexed
            (fn [i v]
              (assoc v :index i))))))))

(defn app:kafka:data:print-table [& args]
  (println "============== Print Data Table ==============")
  (print "Args: " (pr-str args))
  (println (apply app:kafka:data:ascii-table args)))

(comment
  ;; Набір функцій роботи з стейтом
  ;; задля демонстрації роботи з даними.
  ;; ------------------
  ;; State revalidation
  (app:state:reset)
  ;; Add new filters
  (app:filter:add {:topic "books" :q "b"})
  (app:filter:add {:topic "books" :q "Manag"})
  (app:filter:add {:topic "books" :q "SICP"})
  ;; Remove filter
  (app:filter:remove 0)
  (app:filter:remove 1)
  (app:filter:remove 2)
  (app:filter:remove 3)
  ;; Debug filter list
  (app:filter:print-table)
  ;; ------------
  ;; Kafka events
  (app:kafka:handle-data "books" "SICP")
  (app:kafka:handle-data "books" "Managment for dummies")
  (app:kafka:handle-data "books" "Zabuzko, Poliovi doslidzennia ukrainskoho seksu")
  (app:kafka:handle-data "songs" "G.Holst, Planets")
  (app:kafka:handle-data "songs" "DziDzio, Ja cie kocham")
  (app:kafka:handle-data "songs" "Britni, Oohhh i did it again")
  ;; Debug data table
  (app:kafka:data:print-table)
  (app:kafka:data:print-table :filter-id 5))


(comment
  ;; Тестовий кейс описаний також в
  ;; Мейк-Файлі для перевірки виключно
  ;; логічної частини процесингу
  ;; без домішок АПІ та Кафки.
  ;; -----------------------
  ;; Right Filter Scenarious
  ;; -----------------------
  (app:state:reset)
  (app:kafka:handle-data "books" "SICP")
  (app:kafka:handle-data "books" "Computer SICence")
  (app:filter:add {:topic "books" :q "SIC"})
  (app:kafka:handle-data "books" "ANALogies for Critical Thinking Grade 4")
  (app:kafka:handle-data "books" "Understanding ANALysis")
  (app:filter:add {:topic "books" :q "ING"})
  (app:kafka:handle-data "books" "calculating with ANALytic Geometry")
  (app:filter:print-table)
  (app:kafka:data:print-table :filter-id 1))


;; ██   ██ ███████ ██   ██ 
;; ██  ██  ██      ██  ██  
;; █████   █████   █████   
;; ██  ██  ██      ██  ██  
;; ██   ██ ██      ██   ██
;; =======================

;; Проблеми та специфіка імплементації:
;; - Кафка запускається в треді щоб не лочити процес
;; - Для певності був доданий КіллПіл щоб убити
;;   висячий процес-підключення кафки.
;; - при ерроі є обслуха закривання конектора.
(defn run-kafka-polling-process [consumer-atom handler topics]
  (println "Run Kafka Polling Process")
  (async/thread
    (with-open
      [CONSUMER-OBJ
       (KafkaConsumer/new
         (hash-map
           ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG         "localhost:9092"
           ConsumerConfig/GROUP_ID_CONFIG                  "MyServiceConsumerGroup"
           ConsumerConfig/AUTO_COMMIT_INTERVAL_MS_CONFIG   "1000"
           ConsumerConfig/AUTO_OFFSET_RESET_CONFIG         "earliest"
           ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG    "org.apache.kafka.common.serialization.StringDeserializer"
           ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG  "org.apache.kafka.common.serialization.StringDeserializer"))]
      (reset! consumer-atom CONSUMER-OBJ)
      (KafkaConsumer/.subscribe CONSUMER-OBJ topics)
      (KafkaConsumer/.seekToBeginning CONSUMER-OBJ (KafkaConsumer/.assignment CONSUMER-OBJ))
      (try
        (loop [^ConsumerRecords records nil]
          (doseq [^ConsumerRecord record (seq records)]
            (let [^String topic (ConsumerRecord/.topic record)
                  ^String value (ConsumerRecord/.value record)]
              (when (= value "KILL1")
                (throw (ex-info "KILLPILL" {})))
              (async/go (handler topic value))))
          (recur ^ConsumerRecords
            (KafkaConsumer/.poll CONSUMER-OBJ
              (java.time.Duration/ofMillis 500))))
        (catch Exception _e
          (KafkaConsumer/.close CONSUMER-OBJ)
          (println (str "Close polling connection for " CONSUMER-OBJ))))
      CONSUMER-OBJ)))


;; ██   ██ ████████ ████████ ██████  
;; ██   ██    ██       ██    ██   ██ 
;; ███████    ██       ██    ██████  
;; ██   ██    ██       ██    ██      
;; ██   ██    ██       ██    ██      
;; =================================
;;
;; Насправді не вистачило час на гарний
;; еррор хендлінг
(defroutes filter-handler
  (GET "/filter" {{:keys [id]} :params :as all}
    (try
      (if id
        (let [parsed-id (parse-long id)]
          (if parsed-id
            {:status 200
             :headers {"Content-Type" "text/plain"}
             :body (app:kafka:data:ascii-table :filter-id parsed-id)}
            (throw (ex-info (str "Unparsable 'Id' value: " id) {}))))
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body (app:filter:ascii-table)})
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body (ex-message e)})))
  (POST "/filter" {params :params}
    (try
      (when (not-empty params)
        (let [{:keys [topic q]} params]
          {:status 202
           :headers {"Content-Type" "text/plain"}
           :body (if-let [v (app:filter:add {:topic topic :q q})]
                   (str v)
                   "Validation Error or other error... but it can be way more better")}))
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body (ex-message e)})))
  (DELETE "/filter" {{:keys [id]} :params}
    (try
      (if id
        (let [parsed-id (parse-long id)]
          (if parsed-id
            (if-let [returned-id (app:filter:remove parsed-id)]
              {:status 202
               :headers {"Content-Type" "text/plain"}
               :body (str returned-id)}
              {:status 200
               :headers {"Content-Type" "text/plain"}
               :body (str "unexist" parsed-id)})
            (throw (ex-info (str "Uncorrect or empty 'id' key, look on example: http://...filter/id=31" id) {}))))
        (throw (ex-info (str "Uncorrect or empty 'id' key, look on example: http://...filter/id=31" id) {})))
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body (ex-message e)})))
  (POST "/debug" request
    (str "DEBUG\n" (with-out-str (clojure.pprint/pprint request))))
  (compojure.route/not-found " not found 404 "))

(def handler
  (-> filter-handler
    (ring-middleware/wrap-defaults ring-middleware/api-defaults)
    (ring-middleware-json/wrap-json-body)
    (ring-middleware-json/wrap-json-params)
    (ring-middleware-json/wrap-json-response)))

(defn run-http-server-process [server-atom]
  (println "Run Server http://localhost:4000")
  (reset! server-atom
    (org.httpkit.server/run-server
      #'handler
      {:port 4000})))

;; ----
;; MAIN
;; ----

(def http-server-object    (atom nil))
(def kafka-consumer-object (atom nil))

(defn -main [& args]
  (run-http-server-process http-server-object)
  (run-kafka-polling-process kafka-consumer-object app:kafka:handle-data ["books"]))

(comment
  (-main)
  ;; -----------------
  ;; Close HTTP Server
  (@http-server-object)
  ;; ---------------------
  ;; Close Kafka connector
  (KafkaConsumer/.close @kafka-consumer-object))
