package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.mockdata.ai.AiMockException;
import com.springaimcpservercommon.mockdata.ai.AiMockGenerator;
import com.springaimcpservercommon.mockdata.ai.AiResponseParser;
import com.springaimcpservercommon.mockdata.ai.ClassDescriber;
import com.springaimcpservercommon.mockdata.engine.FieldGap;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the host's Spring AI model for a batch of mock objects. The prompt carries the class description and the
 * developer's {@code @FakeAi} hints only; no data of the host is sent. The call is bounded by a timeout.
 */
final class SpringAiMockGenerator implements AiMockGenerator {

    private static final String SYSTEM = """
            You generate mock data for software tests. Answer with exactly one JSON array and nothing else: no prose, no code fence.
            Every element is one object whose property names are the field names you are given. Respect the field types: numbers
            as numbers, booleans as booleans, dates as ISO-8601 text, enums as one of the listed constants. Make the values realistic
            and varied but entirely fictional: never real people, real credentials, real card numbers or real addresses.
            """;

    private final ChatClient client;
    private final Duration timeout;

    SpringAiMockGenerator(ChatClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public <T> List<T> generate(Class<T> type, int count, List<FieldGap> hints) throws AiMockException {
        StringBuilder user = new StringBuilder("Generate ").append(count).append(" mock objects of type ").append(type.getSimpleName())
                .append(" with these fields:\n").append(ClassDescriber.describe(type));
        List<FieldGap> wanted = hints.stream().filter(h -> !h.hint().isEmpty()).toList();
        if (!wanted.isEmpty()) {
            user.append("\nThe values of these fields matter most:\n");
            wanted.forEach(h -> user.append("- ").append(h.path()).append(": ").append(h.hint()).append('\n'));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> answer = CompletableFuture.supplyAsync(
                    () -> client.prompt().system(SYSTEM).user(user.toString()).call().content(), executor);
            return AiResponseParser.parse(answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS), type);
        } catch (TimeoutException e) {
            throw new AiMockException("the AI model did not answer within " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiMockException("interrupted while waiting for the AI model", e);
        } catch (ExecutionException e) {
            throw new AiMockException("the AI model call failed (" + e.getCause().getClass().getSimpleName() + ")", e.getCause());
        }
    }
}
