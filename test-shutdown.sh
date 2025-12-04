#!/bin/bash

echo "=================================================="
echo "SMPP-MLS Graceful Shutdown Test"
echo "=================================================="
echo ""

# Clean and build
echo "Step 1: Building application..."
./gradlew clean bootJar

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful"
echo ""

# Start application
echo "Step 2: Starting application..."
java -jar build/libs/smpp-mls-0.1.0.jar > /tmp/smpp-test.log 2>&1 &
APP_PID=$!

echo "✅ Application started with PID: $APP_PID"
echo "   Log file: /tmp/smpp-test.log"
echo ""

# Wait for startup
echo "Step 3: Waiting 15 seconds for application startup..."
sleep 15

# Check if still running
if ! kill -0 $APP_PID 2>/dev/null; then
    echo "❌ Application died during startup!"
    echo "   Check logs: tail -f /tmp/smpp-test.log"
    exit 1
fi

echo "✅ Application is running"
echo ""

# Send shutdown signal
echo "Step 4: Sending graceful shutdown signal (SIGTERM)..."
kill $APP_PID

echo "   Waiting for graceful shutdown (max 60 seconds)..."

# Wait for shutdown with timeout
TIMEOUT=60
ELAPSED=0
while kill -0 $APP_PID 2>/dev/null && [ $ELAPSED -lt $TIMEOUT ]; do
    sleep 1
    ELAPSED=$((ELAPSED + 1))
    if [ $((ELAPSED % 5)) -eq 0 ]; then
        echo "   ... still shutting down ($ELAPSED seconds elapsed)"
    fi
done

echo ""

# Check result
if kill -0 $APP_PID 2>/dev/null; then
    echo "❌ Application did not stop after $TIMEOUT seconds"
    echo "   Force killing..."
    kill -9 $APP_PID
    exit 1
else
    echo "✅ Application stopped gracefully"
fi

echo ""
echo "=================================================="
echo "Checking shutdown logs..."
echo "=================================================="
echo ""

# Check for shutdown banner
if grep -q "GRACEFUL SHUTDOWN INITIATED" /tmp/smpp-test.log; then
    echo "✅ Shutdown banner found!"
else
    echo "❌ Shutdown banner NOT found!"
fi

if grep -q "@PreDestroy method called" /tmp/smpp-test.log; then
    echo "✅ @PreDestroy method was called"
else
    echo "⚠️  @PreDestroy method was NOT called"
fi

if grep -q "JVM shutdown hook triggered" /tmp/smpp-test.log; then
    echo "⚠️  Shutdown hook was triggered (fallback mechanism)"
else
    echo "✅ Shutdown hook was not needed"
fi

if grep -q "GRACEFUL SHUTDOWN COMPLETE" /tmp/smpp-test.log; then
    echo "✅ Shutdown completed successfully"
else
    echo "❌ Shutdown did not complete properly"
fi

echo ""
echo "=================================================="
echo "Full shutdown sequence:"
echo "=================================================="
echo ""

# Extract shutdown sequence from logs
grep -A 50 "GRACEFUL SHUTDOWN INITIATED\|@PreDestroy method called\|JVM shutdown hook triggered" /tmp/smpp-test.log | head -60

echo ""
echo "=================================================="
echo "Full log available at: /tmp/smpp-test.log"
echo "=================================================="
