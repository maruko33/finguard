output "state_bucket_name" {
  value = aws_s3_bucket.terraform_state.bucket
}

output "github_terraform_plan_role_arn" {
  value = aws_iam_role.github_terraform_plan.arn
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}