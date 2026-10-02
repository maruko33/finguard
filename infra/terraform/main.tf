resource "aws_sqs_queue" "transactions_dlq" {
  name             = var.transactions_dlq_name
  max_message_size = var.max_message_size
}

resource "aws_sqs_queue" "transactions" {
  name                       = var.transactions_queue_name
  max_message_size           = var.max_message_size
  visibility_timeout_seconds = var.visibility_timeout_seconds
  receive_wait_time_seconds  = var.receive_wait_time_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.transactions_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}