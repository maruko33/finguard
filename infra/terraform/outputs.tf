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

#-------------------RDS endpoint--------------------------------
output "rds_endpoint" {
  description = "RDS PostgreSQL endpoint"
  value       = aws_db_instance.postgres.address
}
#-------------------ECR URL-------------------------------------
output "transaction_api_ecr_url" {
  description = "ECR repository URL for the Transaction API"
  value       = aws_ecr_repository.transaction_api.repository_url
}

output "risk_worker_ecr_url" {
  description = "ECR repository URL for the Risk Worker"
  value       = aws_ecr_repository.risk_worker.repository_url
}

#--------------------ALB address--------------------------------
output "alb_dns_name" {
  description = "Public DNS name of the FinGuard ALB"
  value       = aws_lb.transaction_api.dns_name
}