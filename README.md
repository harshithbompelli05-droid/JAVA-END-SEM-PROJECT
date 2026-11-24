# Smart Canteen Management System

Academic project: Java backend (JDBC) + SQLite database + HTML/CSS/JavaScript website.

    Website (HTML + CSS + JavaScript)  ->  Java Backend  ->  JDBC  ->  SQLite (Users | Menu | Orders | Payments)

## Features
- Student: sign up / sign in, browse menu, cart, place order (Cash/UPI/Card), track status, order history, cancel a placed order.
- Admin: sign up (needs admin code) / sign in, manage menu items and prices, availability, update order status, finance dashboard.

## Demo Credentials
| Role    | Email                | Password   |
|---------|----------------------|------------|
| Student | student@canteen.com  | student123 |
| Admin   | admin@canteen.com    | admin123   |

Admin registration code: `CANTEEN-ADMIN-2026` (override with environment variable `ADMIN_CODE`).

## Run Locally
Requirements: JDK 17+ and Maven.

    mvn clean package
    java -jar target/smart-canteen.jar

Open http://localhost:8080. The database file `canteen.db` and demo data are created automatically.

## Deployment
Vercel hosts static websites and short serverless functions; it cannot run this Java server or keep a SQLite file.
Use one of the two options below.

**Option A - everything on one host (simplest).** Deploy the project to Render, Railway or Fly.io using the included `Dockerfile`
(Render: New > Web Service > connect the GitHub repository > Runtime: Docker). The Java server serves the website and the API.

**Option B - website on Vercel, backend elsewhere.**
1. Deploy the backend as in Option A and copy its URL.
2. Edit `frontend/config.js`: `window.API_BASE = "https://your-backend.onrender.com";`
3. In Vercel, import the repository and set **Root Directory** to `frontend` (no build command needed).

Note: on free hosting tiers the SQLite file may reset on restart; demo users and menu are re-created automatically.
Attach a persistent disk and set `DB_PATH` (e.g. `/data/canteen.db`) to keep data permanently.

## Project Structure
    pom.xml, Dockerfile
    src/main/java/com/smartcanteen/  Main.java (server), Api.java (REST + business logic), Db.java (JDBC, schema, seed), Json.java
    frontend/                         index.html, style.css, app.js, config.js
