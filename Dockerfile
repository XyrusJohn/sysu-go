FROM node:24-slim

# Set working directory to /app
WORKDIR /app

# Copy package files from the backend folder
COPY backend/package*.json ./

# Install dependencies
RUN npm ci --omit=dev

# Copy the actual backend code into /app
COPY backend/ .

# Use Railway-provided PORT (default 3001 for Node.js)
ENV PORT=3001

# Ensure we use the port Railway assigns
EXPOSE 3001

# Run the server (files from backend/ copied directly to /app)
CMD ["node", "server.js"]
