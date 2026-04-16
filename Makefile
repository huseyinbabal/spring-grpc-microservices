.PHONY: demo up down logs build

up:
	docker compose up -d --build

down:
	docker compose down -v

logs:
	docker compose logs -f

demo:
	@bash scripts/demo.sh

build:
	mvn -B -ntp verify
