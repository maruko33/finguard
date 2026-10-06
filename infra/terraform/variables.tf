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

#---------------------CIDR partition--------------------

variable "vpc_cidr" {
  description = "CIDR block for the FinGuard VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets"
  type        = list(string)

  default = [
    "10.0.1.0/24",
    "10.0.2.0/24"
  ]
}

variable "app_subnet_cidrs" {
  description = "CIDR blocks for private application subnets"
  type        = list(string)

  default = [
    "10.0.11.0/24",
    "10.0.12.0/24"
  ]
}

variable "db_subnet_cidrs" {
  description = "CIDR blocks for private database subnets"
  type        = list(string)

  default = [
    "10.0.21.0/24",
    "10.0.22.0/24"
  ]
}

#----DP login Info(Password are managed by password manager)--
variable "db_name" {
  description = "FinGuard PostgreSQL database name"
  type        = string
  default     = "finguard"
}

variable "db_username" {
  description = "FinGuard PostgreSQL master username"
  type        = string
  default     = "finguard"
}

#----------------------Task Definition----------------------
variable "image_tag" {
  description = "Container image tag deployed to ECS"
  type        = string
  default     = "v0.8.0-rc1"
}