package com.springaimcpservercommon.loadtest.k6;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.Channel;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Writes {@code kafka.js}: a producer script for the topics the project listens to. Kafka is not a k6 core protocol —
 * the script needs a k6 binary built with <a href="https://github.com/mostafa/xk6-kafka">xk6-kafka</a>
 * ({@code xk6 build --with github.com/mostafa/xk6-kafka}); everything else of the suite runs on stock k6.
 */
final class KafkaScript {

    private KafkaScript() {
    }

    static String render(List<Channel> channels) {
        ObjectNode topics = Documents.json().createObjectNode();
        String brokers = "localhost:9092";
        for (Channel c : channels) {
            if (c.kind() == Channel.Kind.KAFKA) {
                ObjectNode t = topics.putObject(c.send().getFirst());
                t.set("message", c.sample() != null ? c.sample() : Documents.json().createObjectNode().put("sample", "x"));
                brokers = c.source();
            }
        }
        return JsEmitter.GENERATED + """

                // Produces messages to the topics the project consumes (@KafkaListener). Needs a k6 binary with xk6-kafka:
                //   xk6 build --with github.com/mostafa/xk6-kafka        (then run ./k6 instead of k6)
                //   KAFKA_BROKERS=host:9092 ./k6 run -e VUS=5 -e DURATION=30s kafka.js
                // This script is standalone: edit the messages in TOPICS below.
                import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING } from 'k6/x/kafka';
                import { check } from 'k6';

                const brokers = (__ENV.KAFKA_BROKERS || '%s').split(',');
                const TOPICS = %s;
                const writers = {};
                for (const topic of Object.keys(TOPICS)) writers[topic] = new Writer({ brokers, topic });
                const schemaRegistry = new SchemaRegistry();

                export const options = {
                  vus: parseInt(__ENV.VUS || '1', 10),
                  duration: __ENV.DURATION || '10s',
                  thresholds: { checks: ['rate>0.99'] },
                };

                export default function () {
                  for (const topic of Object.keys(TOPICS)) {
                    const body = JSON.stringify(TOPICS[topic].message);
                    const err = writers[topic].produce({
                      messages: [{
                        key: schemaRegistry.serialize({ data: `${__VU}-${__ITER}`, schemaType: SCHEMA_TYPE_STRING }),
                        value: schemaRegistry.serialize({ data: body, schemaType: SCHEMA_TYPE_STRING }),
                      }],
                    });
                    check(err, { [`produced to ${topic}`]: (e) => e === undefined });
                  }
                }

                export function teardown() {
                  for (const w of Object.values(writers)) w.close();
                }
                """.formatted(brokers, topics.toPrettyString());
    }
}
