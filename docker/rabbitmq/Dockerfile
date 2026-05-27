# Custom RabbitMQ image with the delayed-message-exchange plugin pre-installed.
# See DESIGN.md section 11.2.

FROM rabbitmq:3.13-management-alpine

ARG PLUGIN_VERSION=3.13.0

RUN apk add --no-cache curl && \
    curl -L -o /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez \
      https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez && \
    apk del curl

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
