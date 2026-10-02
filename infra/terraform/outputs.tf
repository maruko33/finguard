output "transactions_queue_url" {
  description = "URL of the transaction processing queue"
  value       = aws_sqs_queue.transactions.url
}

output "transactions_queue_arn" {
  description = "ARN of the transaction processing queue"
  value       = aws_sqs_queue.transactions.arn
}

output "transactions_dlq_url" {
  description = "URL of the transaction dead-letter queue"
  value       = aws_sqs_queue.transactions_dlq.url
}

output "transactions_dlq_arn" {
  description = "ARN of the transaction dead-letter queue"
  value       = aws_sqs_queue.transactions_dlq.arn
}