const express = require("express");
const router = express.Router();

// SAP OData gateway URL from env ( Railway )
const SAP_URL = process.env.SAP_URL || "";

// GET /api/sap/shipments — proxies SAP OData to web dashboard
router.get("/shipments", async (req, res) => {
  try {
    if (!SAP_URL) {
      return res.status(503).json({ success: false, message: "SAP gateway URL not configured" });
    }

    const url = SAP_URL.includes("$format=json") ? SAP_URL : `${SAP_URL}?$format=json`;
    const response = await fetch(url, {
      headers: {
        "Accept": "application/json",
        "Connection": "keep-alive",
      },
    });

    if (!response.ok) {
      throw new Error(`SAP proxy error: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    res.status(200).json(data);
  } catch (error) {
    console.error("[SAP Proxy] Failed to fetch shipments:", error.message);
    res.status(502).json({ success: false, message: "SAP service unavailable" });
  }
});

module.exports = router;