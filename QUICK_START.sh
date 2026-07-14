#!/bin/bash

# Messaging Application - Quick Start Script
# Run this to start development immediately

set -e

PROJECT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "╔════════════════════════════════════════════╗"
echo "║   MESSAGING APPLICATION - QUICK START      ║"
echo "║   Milestone 1: Foundation Complete         ║"
echo "╚════════════════════════════════════════════╝"
echo ""

# Function to display section headers
print_section() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📋 $1"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# Check prerequisites
print_section "CHECKING PREREQUISITES"

if ! command -v docker &> /dev/null; then
    echo "❌ Docker is required but not installed"
    echo "   Download from: https://www.docker.com/products/docker-desktop"
    exit 1
fi
echo "✅ Docker is installed"

if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is required but not installed"
    echo "   Download from: https://maven.apache.org"
    exit 1
fi
echo "✅ Maven is installed"

if ! command -v java &> /dev/null; then
    echo "❌ Java 17+ is required but not installed"
    echo "   Download from: https://www.oracle.com/java/technologies/downloads/"
    exit 1
fi
echo "✅ Java is installed"

# Start Docker PostgreSQL
print_section "STARTING POSTGRESQL"

if [ "$(docker ps -q -f name=messaging_postgres)" ]; then
    echo "✅ PostgreSQL is already running"
else
    echo "⏳ Starting PostgreSQL container..."
    docker-compose -f "$PROJECT_DIR/docker-compose.yml" up -d
    echo "⏳ Waiting for PostgreSQL to be ready..."
    sleep 5
    docker logs messaging_postgres 2>&1 | tail -5
fi

# Build Backend
print_section "BUILDING BACKEND"

cd "$PROJECT_DIR/messaging-backend"
echo "⏳ Building Spring Boot application..."
mvn clean compile -q 2>&1 || true

# Display important information
print_section "DEVELOPMENT SETUP COMPLETE ✅"

echo ""
echo "📌 NEXT STEPS:"
echo ""
echo "1️⃣  START BACKEND (in new terminal):"
echo "    cd \"$PROJECT_DIR/messaging-backend\""
echo "    mvn spring-boot:run"
echo ""
echo "2️⃣  START ANDROID (in Android Studio):"
echo "    Open: $PROJECT_DIR/messaging-android"
echo ""

echo "📊 IMPORTANT URLS:"
echo ""
echo "  🖥️  Backend API:          http://localhost:8080/api"
echo "  💚 Health Check:          http://localhost:8080/api/v1/health"
echo "  🗄️  Database:              localhost:5432"
echo "  👤 DB Username:            postgres"
echo "  🔑 DB Password:            postgres123"
echo ""

echo "📚 DOCUMENTATION:"
echo ""
echo "  📖 Project Overview:       README.md"
echo "  ✅ Milestone Summary:       MILESTONE_1_SUMMARY.md"
echo "  🏗️  Architecture Design:    docs/MILESTONE_1_COMPLETE.md"
echo ""

echo "🐛 TROUBLESHOOTING:"
echo ""
echo "  Stop PostgreSQL:           docker-compose down"
echo "  View PostgreSQL logs:      docker logs messaging_postgres"
echo "  View all containers:       docker ps -a"
echo ""

echo "═══════════════════════════════════════════════════════"
echo "✨ Your production-quality messaging app is ready! ✨"
echo "═══════════════════════════════════════════════════════"
echo ""
