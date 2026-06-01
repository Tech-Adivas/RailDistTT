# =============================================================================
# Stage 1: Build Angular application
# Node 22 Alpine keeps the builder image lean and reproducible.
# =============================================================================
FROM node:22-alpine AS builder
WORKDIR /app

# Copy package manifests first to maximise layer cache reuse.
# npm ci only re-runs when package-lock.json changes.
COPY package.json package-lock.json ./
RUN npm ci --prefer-offline

# Copy the rest of the application source and build for production.
# Angular outputs content-hashed bundles under dist/operator-console/browser/.
COPY . .
RUN npm run build:prod

# =============================================================================
# Stage 2: Serve with nginx
# nginx:1.27-alpine is a minimal, regularly-patched image.
# The builder node_modules and source are not present in this stage.
# =============================================================================
FROM nginx:1.27-alpine AS runtime

# Remove the default nginx virtual-host config so our custom one is the only one.
RUN rm /etc/nginx/conf.d/default.conf

# Install our custom nginx configuration (SPA routing, security headers,
# aggressive asset caching, health check endpoint).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the compiled Angular app from the builder stage.
COPY --from=builder /app/dist/operator-console/browser /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
