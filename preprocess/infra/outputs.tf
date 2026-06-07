output "bucket" {
  value = aws_s3_bucket.data.bucket
}

output "base_url" {
  description = "Prefix the app fetches manifest.json and lines/<line>.json from"
  value       = "https://${aws_s3_bucket.data.bucket}.s3.${var.region}.amazonaws.com"
}

output "deploy_role_arn" {
  description = "role-to-assume for the GitHub Actions deploy workflow"
  value       = aws_iam_role.deploy.arn
}
