package com.finguard.transactionapi.transaction.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import tools.jackson.databind.json.JsonMapper;

@Component
public class SqsTransactionEventPublisher
        implements TransactionEventPublisher {

    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final String queueUrl;

    public SqsTransactionEventPublisher(
            SqsClient sqsClient,
            JsonMapper jsonMapper,
            @Value("${finguard.sqs.transaction-queue-url}") String queueUrl) {

        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(TransactionCreatedEvent event) {
        String messageBody = jsonMapper.writeValueAsString(event);

        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();

        sqsClient.sendMessage(request);
    }
}