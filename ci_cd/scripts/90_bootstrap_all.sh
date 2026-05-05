#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${SCRIPT_DIR}/00_prepare_host.sh"
"${SCRIPT_DIR}/10_install_k3s.sh"
"${SCRIPT_DIR}/20_install_gitlab_prereqs.sh"
"${SCRIPT_DIR}/30_install_gitlab.sh"
"${SCRIPT_DIR}/40_install_jenkins.sh"
"${SCRIPT_DIR}/50_bootstrap_gitlab_project.sh"
"${SCRIPT_DIR}/60_install_business_dependencies.sh"
"${SCRIPT_DIR}/80_apply_business_stack.sh"
"${SCRIPT_DIR}/95_smoke_test.sh"
