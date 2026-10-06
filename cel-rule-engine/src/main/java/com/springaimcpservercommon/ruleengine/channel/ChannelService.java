package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher.Result;
import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Model.ActionBinding;
import com.springaimcpservercommon.ruleengine.domain.Model.Channel;
import com.springaimcpservercommon.ruleengine.eval.Results.Dispatch;
import com.springaimcpservercommon.ruleengine.repo.ChannelRepository;
import com.springaimcpservercommon.ruleengine.repo.LogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the channels bound to an outcome. A channel is used at most once per evaluation and a failing channel never
 * fails the evaluation: every attempt is written to the dispatch log.
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelRepository channels;
    private final LogRepository logs;
    private final Map<ChannelType, ChannelDispatcher> dispatchers = new EnumMap<>(ChannelType.class);

    public ChannelService(ChannelRepository channels, LogRepository logs, List<ChannelDispatcher> all) {
        this.channels = channels;
        this.logs = logs;
        all.forEach(d -> dispatchers.put(d.type(), d));
    }

    /**
     * Dispatches the bindings of one outcome.
     *
     * @param bindings     the action bindings that fired
     * @param notification what to communicate
     * @param alreadyUsed  channel ids already used in this evaluation (updated)
     * @return what happened per channel
     */
    public List<Dispatch> dispatch(List<ActionBinding> bindings, Notification notification, Set<Long> alreadyUsed) {
        List<Dispatch> out = new java.util.ArrayList<>();
        for (ActionBinding b : bindings) {
            if (!alreadyUsed.add(b.channelId())) {
                continue;
            }
            Channel channel = channels.channel(b.channelId()).orElse(null);
            if (channel == null || !channel.active()) {
                continue;
            }
            Result result;
            try {
                result = dispatchers.get(channel.type()).dispatch(channel, notification);
            } catch (RuntimeException e) {
                result = Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.info("channel {} ({}) -> {}", channel.name(), channel.type(), result.status());
            logs.saveDispatch(notification.evaluationId(), channel.id(), channel.type().name(), result.status(),
                    result.detail());
            out.add(new Dispatch(channel.name(), channel.type().name(), result.status(), result.detail()));
        }
        return out;
    }

    /**
     * @return a fresh set for {@link #dispatch}'s {@code alreadyUsed}
     */
    public static Set<Long> newUsedSet() {
        return new HashSet<>();
    }
}
