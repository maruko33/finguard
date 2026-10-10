#--------------Risk work lambda CloudWatch--------------------------
resource "aws_iam_role_policy_attachment" "lambda_logging" {
  role       = aws_iam_role.risk_worker_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

#-----------------Lambda function for risk worker---------------------


resource "aws_lambda_function" "risk_worker_lambda" {
  function_name = "finguard-risk-worker"

  filename         = data.archive_file.risk_worker_lambda.output_path
  source_code_hash = data.archive_file.risk_worker_lambda.output_base64sha256

  role    = aws_iam_role.risk_worker_lambda.arn
  runtime = "python3.12"

  handler = "risk_worker.lambda_handler.lambda_handler"

  memory_size = 128
  timeout     = 10
}

data "archive_file" "risk_worker_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../../services/risk-worker/src"
  output_path = "${path.module}/risk_worker_lambda_payload.zip"
}

#----------------Lambda Event Source Mapping -------------------
resource "aws_lambda_event_source_mapping" "risk_worker_sqs" {
  event_source_arn = aws_sqs_queue.transactions.arn

  function_name = aws_lambda_function.risk_worker_lambda.arn

  batch_size = 1
  enabled    = true

  depends_on = [
    aws_iam_role_policy.lambda_sqs
  ]
}

