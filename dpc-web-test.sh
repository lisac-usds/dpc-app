#!/bin/bash
set -e

echo "┌─────────────────────────┐"
echo "│                         │"
echo "│  Running Website Tests  │"
echo "│                         │"
echo "└─────────────────────────┘"

# Build the container
make website

# Run the tests
docker-compose -f dpc-web/docker-compose.yml up start_core_dependencies
docker-compose -f dpc-web/docker-compose.yml run web bundle exec rails db:create db:migrate RAILS_ENV=test
docker-compose -f dpc-web/docker-compose.yml run web bundle exec rails spec

echo "┌──────────────────────────────────┐"
echo "│                                  │"
echo "│    All Website Tests Complete    │"
echo "│                                  │"
echo "└──────────────────────────────────┘"
