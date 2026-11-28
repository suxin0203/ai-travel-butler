package com.suxin.travel.traveljava.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提醒链路的 MQ 拓扑：
 * 业务交换机 → 队列（带 DLX 参数）→ 消费失败重试耗尽后自动进死信队列。
 * 全部持久化：Broker 重启消息不丢。
 * 死信是"消费失败"的兜底出口，排查与人工补偿都在 DLQ 里做。
 */
@Configuration
public class RabbitConfig {

    public static final String REMINDER_EXCHANGE = "travel.reminder.exchange";
    public static final String REMINDER_QUEUE = "travel.reminder.queue";
    public static final String REMINDER_ROUTING = "reminder";

    public static final String DEAD_LETTER_EXCHANGE = "travel.reminder.dlx";
    public static final String DEAD_LETTER_QUEUE = "travel.reminder.dlq";
    public static final String DEAD_LETTER_ROUTING = "reminder.dead";

    @Bean
    public DirectExchange reminderExchange() {
        return new DirectExchange(REMINDER_EXCHANGE);
    }

    /** 业务队列：声明 DLX 参数——被 nack(requeue=false) 的消息自动路由到死信交换机 */
    @Bean
    public Queue reminderQueue() {
        return QueueBuilder.durable(REMINDER_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING)
                .build();
    }

    @Bean
    public Binding reminderBinding() {
        return BindingBuilder.bind(reminderQueue()).to(reminderExchange()).with(REMINDER_ROUTING);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_ROUTING);
    }
}
