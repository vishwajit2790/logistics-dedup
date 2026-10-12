// Run with:  mongosh "mongodb://localhost:27017" seed_logistics.js
// Creates database "logistics" with a 5-level hierarchy:
// LogisticsNetwork > RegionalHub > TransportFleet > ShippingContainer > Package

const d = db.getSiblingDB("logistics");
["logistics_networks", "regional_hubs", "transport_fleets", "shipping_containers", "packages"]
  .forEach(c => d[c].drop());

// Deterministic pseudo-random so every run produces the same data
let seed = 42;
const rand = () => (seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296;
const pick = arr => arr[Math.floor(rand() * arr.length)];
const between = (a, b, dp = 1) => +(a + rand() * (b - a)).toFixed(dp);
const pad = (n, w = 3) => String(n).padStart(w, "0");

// ---------- Level 1: LogisticsNetwork ----------
const network = {
  _id: "NET-FEDEX",
  type: "LogisticsNetwork",
  level: 1,
  name: "FedEx Global",
  headquarters: { city: "Memphis", country: "USA" },
  founded: 1971,
  path: []
};
d.logistics_networks.insertOne(network);

// ---------- Level 2: RegionalHub ----------
const hubDefs = [
  { code: "APAC", name: "Asia-Pacific Hub", city: "Guangzhou", country: "China" },
  { code: "EU",   name: "European Hub",     city: "Cologne",   country: "Germany" },
  { code: "NA",   name: "North America Hub", city: "Indianapolis", country: "USA" },
  { code: "MEA",  name: "Middle East & Africa Hub", city: "Dubai", country: "UAE" }
];
const hubs = hubDefs.map(h => ({
  _id: `HUB-${h.code}`,
  type: "RegionalHub",
  level: 2,
  name: h.name,
  location: { city: h.city, country: h.country },
  networkId: network._id,
  path: [network._id]
}));
d.regional_hubs.insertMany(hubs);

// ---------- Level 3: TransportFleet ----------
const fleetDefs = [
  { suffix: "CARGO", name: "Custom Cargo Fleet",  mode: "Sea/Road", vehicles: [40, 120] },
  { suffix: "AIR",   name: "Express Flight Fleet", mode: "Air",      vehicles: [10, 60] }
];
const fleets = [];
hubs.forEach(hub => {
  const code = hub._id.replace("HUB-", "");
  fleetDefs.forEach(f => fleets.push({
    _id: `FLT-${code}-${f.suffix}`,
    type: "TransportFleet",
    level: 3,
    name: `${code} ${f.name}`,
    mode: f.mode,
    vehicleCount: Math.round(between(f.vehicles[0], f.vehicles[1], 0)),
    status: "ACTIVE",
    hubId: hub._id,
    path: [network._id, hub._id]
  }));
});
d.transport_fleets.insertMany(fleets);

// ---------- Level 4: ShippingContainer ----------
let conNo = 70; // so that "CON-77" exists
const containers = [];
fleets.forEach(fleet => {
  for (let i = 0; i < 3; i++, conNo++) {
    containers.push({
      _id: `CON-${conNo}`,
      type: "ShippingContainer",
      level: 4,
      name: `Freight Box #CON-${conNo}`,
      containerType: pick(["20ft Standard", "40ft Standard", "40ft High Cube", "Reefer", "ULD Air Container"]),
      capacityKg: pick([5000, 12000, 26000, 28000]),
      sealNumber: `SEAL-${Math.floor(rand() * 900000 + 100000)}`,
      status: pick(["LOADING", "IN_TRANSIT", "ARRIVED", "CUSTOMS_HOLD"]),
      fleetId: fleet._id,
      path: [...fleet.path, fleet._id]
    });
  }
});
d.shipping_containers.insertMany(containers);

// ---------- Level 5: Package ----------
const cities = ["Tokyo", "Singapore", "Sydney", "Berlin", "Paris", "Madrid", "New York",
                "Chicago", "Toronto", "Dubai", "Cairo", "Nairobi", "Mumbai", "Seoul"];
const people = ["A. Tanaka", "L. Chen", "M. Rossi", "S. Patel", "J. Smith", "K. Müller",
                "N. Okafor", "R. Silva", "E. Johansson", "H. Al-Farsi"];
let pkgNo = 1001;
const packages = [];
containers.forEach(con => {
  for (let i = 0; i < 4; i++, pkgNo++) {
    const origin = pick(cities);
    let dest = pick(cities);
    while (dest === origin) dest = pick(cities);
    packages.push({
      _id: `PKG-${pkgNo}`,
      type: "Package",
      level: 5,
      trackingId: `PKG-${pkgNo}`,
      description: `Box with tracking ID #PKG-${pkgNo}`,
      weightKg: between(0.5, 45),
      dimensionsCm: { l: between(10, 80, 0), w: between(10, 60, 0), h: between(5, 50, 0) },
      serviceLevel: pick(["Express", "Priority", "Economy", "Freight"]),
      status: pick(["PICKED_UP", "IN_CONTAINER", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"]),
      origin, destination: dest,
      sender: pick(people),
      recipient: pick(people),
      createdAt: new Date(Date.now() - Math.floor(rand() * 14 * 86400000)),
      containerId: con._id,
      path: [...con.path, con._id]
    });
  }
});
d.packages.insertMany(packages);

// ---------- Indexes ----------
d.regional_hubs.createIndex({ networkId: 1 });
d.transport_fleets.createIndex({ hubId: 1 });
d.shipping_containers.createIndex({ fleetId: 1 });
d.packages.createIndex({ containerId: 1 });
d.packages.createIndex({ trackingId: 1 }, { unique: true });
d.packages.createIndex({ path: 1 });   // find all packages under any ancestor

// ---------- Summary ----------
printjson({
  logistics_networks: d.logistics_networks.countDocuments(),
  regional_hubs: d.regional_hubs.countDocuments(),
  transport_fleets: d.transport_fleets.countDocuments(),
  shipping_containers: d.shipping_containers.countDocuments(),
  packages: d.packages.countDocuments()
});

/* Example queries
// All packages under the Asia-Pacific hub:
db.packages.find({ path: "HUB-APAC" })

// Full ancestry of one package:
db.packages.aggregate([
  { $match: { _id: "PKG-1001" } },
  { $lookup: { from: "shipping_containers", localField: "containerId", foreignField: "_id", as: "container" } },
  { $unwind: "$container" },
  { $lookup: { from: "transport_fleets", localField: "container.fleetId", foreignField: "_id", as: "fleet" } },
  { $unwind: "$fleet" },
  { $lookup: { from: "regional_hubs", localField: "fleet.hubId", foreignField: "_id", as: "hub" } },
  { $unwind: "$hub" }
])
*/
