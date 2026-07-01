# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki

variable "region" {
  description = "AWS region (eu-central-1 / Frankfurt is closest to Warsaw)"
  type        = string
  default     = "eu-central-1"
}

variable "bucket_name" {
  description = "Globally-unique S3 bucket name hosting the per-line JSON"
  type        = string
  default     = "jak-mam-jechac-data"
}

variable "github_repo" {
  description = "Exact owner/name allowed to deploy (forks/other repos won't match)"
  type        = string
  default     = "mccartney/jak-mam-jechac"
}

variable "github_branch" {
  description = "Only this branch of the repo may assume the deploy role"
  type        = string
  default     = "main"
}
