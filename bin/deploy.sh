#!/bin/sh
# One-time setup: copy bin/heroku-jvm-application-deployer-4.0.12.jar
set -e
lein do clean, uberjar
HEROKU_API_KEY=$(heroku auth:token) java -jar bin/heroku-jvm-application-deployer-4.0.12.jar --app=eli target/uberjar/eli-standalone.jar
