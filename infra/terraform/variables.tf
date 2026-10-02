variable "aws_region" {
  description = "AWS region for FinGuard infrastructure"
  type        = string
  default     = "ca-central-1"
}

variable "transactions_queue_name" {
  description = "Name of the transaction processing queue"
  type        = string
  default     = "finguard-transactions"
}

variable "transactions_dlq_name" {
  description = "Name of the transaction dead-letter queue"
  type        = string
  default     = "finguard-transactions-dlq"
}

variable "visibility_timeout_seconds" {
  description = "How long an SQS message stays invisible after being received"
  type        = number
  default     = 30
}

variable "receive_wait_time_seconds" {
  description = "Long polling duration for the transaction queue"
  type        = number
  default     = 20
}

variable "max_receive_count" {
  description = "Number of failed receives before a message is moved to the DLQ"
  type        = number
  default     = 3
}

variable "max_message_size" {
  description = "Maximum SQS message size in bytes"
  type        = number
  default     = 1048576
}