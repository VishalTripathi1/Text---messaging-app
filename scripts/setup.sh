#!/bin/bash

# Messaging Application - Setup Script for macOS
# This script sets up the development environment

set -e

echo "🚀 Messaging Application - Development Setup"
echo "==========================================="

# Check prerequisites
echo "✅ Checking prerequisites..."

if ! command -v java &> /dev/null; then
    echo "❌ Java 17+ is required. Please install JDK 17"
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo "❌ Docker is required. Please install Docker"
    exit 1
fi

if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is required. Please install Maven"
    exit 1
fi

echo "✅ All prerequisites installed"

# Start Docker PostgreSQL
echo ""
echo "🐳 Starting PostgreSQL container..."
docker-compose up -d

echo "⏳ Waiting for PostgreSQL to be ready..."
sleep 10

# Build backend
echo ""
echo "🏗️  Building Spring Boot backend..."
cd messaging-backend
mvn clean install -q
echo "✅ Backend built successfully"

echo ""
echo "🎉 Setup Complete!"
echo ""
echo "📋 Next Steps:"
echo "  1. Backend: cd messaging-backend && mvn spring-boot:run"
echo "  2. Android: Open messaging-android in Android Studio"
echo ""
echo "📚 Documentation:"
echo "  - API Docs: http://localhost:8080/swagger-ui.html"
echo "  - Database: localhost:5432 (postgres/postgres123)"
echo ""
