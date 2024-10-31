#    ███    ███ ██   ██
#    ████  ████ ██  ██
#    ██ ████ ██ █████
#    ██  ██  ██ ██  ██
# ██ ██      ██ ██   ██

# ----------
# -- ECHO --
# ----------
BRed=\033[1;31m
BGreen=\033[1;32m
BYellow=\033[1;33m
BBlue=\033[1;34m
BPurple=\033[1;35m
BCyan=\033[1;36m
BWhite=\033[1;37m
NC=\033[0m

# ------------
# -- COMMON --
# ------------
TOPLEVEL=.
SHELL=env bash
LEIN=lein
DOCKER_COMPOSE=docker-compose
DOCKER=docker

# ------------------------
# ██████   ██████   ██████
# ██   ██ ██    ██ ██
# ██   ██ ██    ██ ██
# ██   ██ ██    ██ ██
# ██████   ██████   ██████
# ========================
# * About
#  Даний Makefile описує інстуркцію інсталяції
#  та запуску проекту. Поза даною інстуркцією
#  звісно є інші таргети, поблизу котрих буде пояснення
#  що та як вони роблять та для чого воно було
#  потрібно.
#
# * Та почнемо з інсталяції
#
# 1. Клонуємо проєкт
#
#    `git clone https://github.com/SerhiiRI/r1024.git`
#
# 2. Таргет що стягує контейнер з кафкою та інсталить депи на проект
#
#    `make install`
#
# 3. Піднімає інфраструктуру з одним контейнером, творить один топік "books" та запускає серверок.
#
#    `make up`
#
# 4. Запускає тестовий сценарій що повинен показати як вся логіка працює в живу.
#
#    `make test`
#
# 5. Після завершення роботи коли вся ця річ вже не здалася робимо чистку
#    Гасимо контейнер з кафкою
#
#    `make down`
#
#    Видаляємо імедж
#
#    `make docker/images/rm`
#
# * FAQ
#  > Чому Makefile?
#  < Адже це вірний тул що дозволяє легко ділити проект
#    його задачі та цілі, красиво поєднуючи між собою
#    а) параметризацію, б) виконання, в) опис процесів
#    г) свободу розширення.
#
#  > Чому контейнер кафки а не інші деривативи?
#  < Оскільки я вчився цій технології на бігу розумним
#    було вживати базову версію даної програмки.
#
#  > Чому провайдер для даних не описаний в кложі?
#  < Гадаю певного роду динаміка на рівні скрипта та
#    конфігурації дозволить створювати гарні динамічні
#    тестові кейси. Але це звісно суб'єктивно. Гадаю
#    з кодовим провайдером суттєво легше й приємніше та
#    вимагатиме покривання API
#
#  > Чому Compojure + HTTP-KIT?
#  < Оскільки давно нових застосунків не робив, то рішив
#    "просто на чомусь захостувати старий класичний Рінг"
#    компожур приємний без ускладеннь та HTTP-KIT гарна
#    альтернатива Дджетті котру ніколи не пробував.
#
#  > Malli замість Spec?
#  < просто було швидше.

# ----------
# -- VM's --
# ----------
#
# Таргети що підтягують/усувають імедж Кафки,
# а також запускюать чи зупиняють докер-копоз
# конфіг. Класика.

docker/images/pull:
	@echo -e "${BGreen} Docker Pull Images${NC}"
	$(DOCKER_COMPOSE) pull kafkaMB

docker/images/rm:
	@echo -e "${BGreen} Docker Pull Images${NC}"
	$(DOCKER) image rm apache/kafka

docker/container/up:
	@echo -e "${BGreen} Docker standing up environment...${NC}"
	$(DOCKER_COMPOSE) up -d

docker/container/down:
	@echo -e "${BGreen} Docker standing up...${NC}"
	$(DOCKER_COMPOSE) down

docker/container/kafka/log:
	$(DOCKER_COMPOSE) logs -f kafkaMB

# -----------
# -- Kafka --
# -----------
#
# Тут дозволив собі трішки легкої автоматизації
# Штука в тім що документація казала що в середині
# контейнра вже були потрібні мені скрипти для продюсерів
# та консюмерів, тож я рішив їх перевикористовувати.
#
# Класична функа в Мейкфайлі що бере два аргумента
#
#  @$(call RunKafka,"books","SICP")
#   _TOPIC = "books"
#   _DATA  = "SICP"
#
# під капотом запускає `docker-compose exec` в папці зі скриптами
# а також запускає продюсера через котрий висилає на _TOPIC
# повідомлення _DATA.
#  Вираз використовується для описання тестового кейса.

RUN_KAFKA=$(DOCKER_COMPOSE) exec --workdir /opt/kafka/bin/ -it kafkaMB

define RunKafka
	$(eval $@_TOPIC = $(1))
	$(eval $@_DATA = $(2))
	@echo -e "${BCyan}Adding message '${$@_DATA}' into topic '${$@_TOPIC}'${NC}"
	$(RUN_KAFKA) bash -c 'echo "${$@_DATA}" | ./kafka-console-producer.sh --bootstrap-server "localhost:9092" --topic "${$@_TOPIC}"'
endef

kafka/bash:
	$(RUN_KAFKA) bash
kafka/topic/books/new:
	$(RUN_KAFKA) ./kafka-topics.sh --bootstrap-server "localhost:9092" --create --topic books
kafka/topic/books/produce/interactive:
	$(RUN_KAFKA) ./kafka-console-producer.sh --bootstrap-server "localhost:9092" --topic books
kafka/topic/books/consume/interactive:
	$(RUN_KAFKA) ./kafka-console-consumer.sh --bootstrap-server "localhost:9092" --topic books --from-beginning

# ------------
# -- BACK's --
# ------------
#
# Таргети для роботи з сервісом. Багато тут роботи немає
# лишень запуск команди що підкчує Джарівські депи та
# друга для власне запуску сервера
service/deps:
	cd $(TOPLEVEL)/service && $(LEIN) deps

service/run:
	cd $(TOPLEVEL)/service && $(LEIN) run

# Тут цікавіше, це команди що можна запустити й потестити
# як працює АПІ сервіса.

service/test/post:
	@echo -e "${BPurple}Test PUT new {'topic': 'books', 'q': 'DUPA'} filter${NC}"
	@curl -d '{"topic": "books", "q": "DUPA"}' -H "Content-Type: application/json" -X POST "http://localhost:4000/filter"
	@sleep 0.5
	@curl "http://localhost:4000/filter"

service/test/delete:
	@echo -e "${BPurple}Test Delete by ID filter${NC}"
	@curl -X DELETE "http://localhost:4000/filter?id=ffsdaoifjdsa"
	@sleep 0.5
	@curl "http://localhost:4000/filter"

service/test/get:
	@echo -e "${BPurple}Test api filter${NC}"
	@curl "http://localhost:4000/filter"
	@echo -e "\n${BPurple}Test api filter?id=1${NC}"
	@curl "http://localhost:4000/filter?id=1"

# Я не писав хелзчека для запуску Докер-Композа, а в
# мене коп'ютер швидкий наче санкції заходу, тож
# чотирьох секунд повністю вистачить для апстарту пустої
# кафки.
#  Причина? Друга команда творить Топік Буукс, і почнуть в
#  консолі сипатися помилки.
sleep-4:
	sleep 4


# ----------
# -- Main --
# ----------


install: docker/images/pull service/deps
up: docker/container/up sleep-4 kafka/topic/books/new service/run
down: docker/container/down

#
#  Тут є малий роз'їзд з заданою задачею(факап). Про фікс написано в коді.
# Дана програма при запиті на /filters?id=2 повертає всі відфільтровані
# рекорди ФІЛЬТРАМИ що були створенні ДО МОМЕНТУ створення вказаного `id=2`
#
#  - Якщо ми зробимо /filter?id=0 (ІД що дістане фільтр {"topic": "books", "q": "SIC"})
#    то ми отримаємо всі книги що підадають під паттерн ТА БУЛИ ВНЕСЕНІ ДО МОМЕНТУ ТВОРЕННЯ ФІЛЬТРА
#     Return: "Sicp", "computer SIcence"
#
#  - Якщо ми зробимо /filter?id=1 (ІД що дістане фільтр {"topic": "books", "q": "ING"})
#    То запрацюють ВСІ фільтри внесені ДО МОМЕНТУ створення фільтар з `id=2` і нам повернуться
#     Return: "Sicp", "computer SIcence", "ANALogies for Critical Thinking Grade 4", "Understanding ANALysis"
#              втім не буде останньої книги "calculating with ANALytic Geometry" адже  її було внесено пізніше
#              вибраного фільтра.
#
test:
	@$(call RunKafka,"books","SICP");
	@$(call RunKafka,"books","Computer SICence");
	curl -d '{"topic": "books", "q": "SIC"}' -H "Content-Type: application/json" -X POST "http://localhost:4000/filter"; echo -e "\n"
	@$(call RunKafka,"books","ANALogies for Critical Thinking Grade 4");
	@$(call RunKafka,"books","Understanding ANALysis");
	curl -d '{"topic": "books", "q": "ING"}' -H "Content-Type: application/json" -X POST "http://localhost:4000/filter"; echo -e "\n"
	@$(call RunKafka,"books","calculating with ANALytic Geometry");
	@curl "http://localhost:4000/filter"
	@curl "http://localhost:4000/filter?id=1"
