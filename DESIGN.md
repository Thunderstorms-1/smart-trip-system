# SmartTrip Design Principles

## 1. Design North Star

SmartTrip is not an admin dashboard.

SmartTrip is an immersive travel product where:

- the journey is the visual protagonist;
- photography builds emotion;
- maps build spatial understanding;
- UI panels support decisions and actions;
- data becomes visual information rather than text noise.

Current approved direction:

Immersive Travel Glass UI + Map-first Travel OS.

---

## 2. Reference-first Design

Do not begin UI work from abstract adjectives such as:

- premium
- natural
- editorial
- modern
- artistic

Before a major visual redesign:

1. collect concrete reference interfaces;
2. identify which references the user actually prefers;
3. explain WHY those references work;
4. extract reusable visual rules;
5. translate those rules into SmartTrip.

A reference is not copied literally.
Its composition logic, hierarchy, density and spatial relationships are translated.

---

## 3. Product Metaphor Before Components

Before designing a page, define what the user should feel they are doing.

Examples:

Home:
entering a journey.

Workbench:
planning a journey directly on a map.

AI Planner:
constructing a trip.

My Trips:
opening a personal travel library.

Profile:
viewing a traveler identity.

Do not begin by asking:
“What cards and forms should this page contain?”

Begin by asking:
“What activity or environment should this page feel like?”

---

## 4. Photography

Travel photography is a first-class design material.

Do not expect typography, gradients or SVG decoration alone to create immersion.

Use imagery to establish:

- destination atmosphere;
- scale;
- light;
- emotion;
- geographic context.

Image quality is part of UI quality.

---

## 5. Maps

In the SmartTrip Workbench:

The map is not a tab.

The map is the workspace canvas.

Timeline, day navigation, weather, POI, route information and intelligence panels may float above it.

The map should remain meaningfully visible.

Do not cover the entire map with panels.

---

## 6. Glass Surfaces

Glass is a hierarchy tool, not a universal component style.

Good uses:

- navigation;
- weather widgets;
- map controls;
- quick planning;
- small status modules.

Avoid glass for:

- long forms;
- dense analysis;
- large reading surfaces;
- Inspector content.

Content-heavy surfaces should use stable high-readability backgrounds.

---

## 7. Cards

SmartTrip does not reject cards.

SmartTrip rejects undifferentiated cards.

Avoid:

- equal cards everywhere;
- card-inside-card layouts;
- identical visual weight for unrelated information.

Cards and modules should differ according to purpose:

- imagery;
- data;
- navigation;
- action;
- analysis;
- recommendation.

Hierarchy matters more than whether a box exists.

---

## 8. Visual Information

Avoid interfaces dominated by text.

When useful, represent information through:

- maps;
- route lines;
- POI pins;
- icons;
- weather symbols;
- thumbnails;
- progress;
- charts;
- numeric indicators;
- status visualization.

Do not turn every feature into:

title + two lines of description.

---

## 9. Typography

Typography supports composition but does not carry the entire visual identity.

Use clear roles:

- Display
- UI
- Data
- Supporting text

Avoid building the entire experience around oversized serif typography.

Artistic quality should also come from:

- photography;
- composition;
- contrast;
- spatial depth;
- information graphics;
- interaction.

---

## 10. Depth

Strong SmartTrip surfaces normally contain multiple visual depths:

1. environmental background;
2. geographic / route layer;
3. floating functional surfaces;
4. content and controls.

Depth should be meaningful.

Do not simulate depth only through excessive shadows.

---

## 11. Light and Dark Rhythm

Premium does not mean permanently dark.

Use scene-based contrast.

Example:

immersive dark photography
→ bright working surface
→ rich visual content
→ quieter section

Pages should breathe through changing light levels.

---

## 12. Workbench

Default Workbench hierarchy:

MAP CANVAS

LEFT:
Day navigation.

CENTER / LEFT FLOATING:
Timeline.

RIGHT:
Insight / Inspector.

Timeline is the primary representation of the itinerary.

Do not render all itinerary fields as an always-expanded form.

Selecting a node should connect:

timeline
↔ map pin
↔ route
↔ Inspector.

---

## 13. Mobile

Mobile is not desktop stacked vertically.

Recompose the experience.

For Workbench:

- map remains visible;
- day navigation becomes compact;
- timeline becomes a drawer/sheet;
- Inspector uses a focused mobile sheet;
- only one dominant panel should compete with the map at a time.

---

## 14. Motion

Motion should explain state and spatial relationships.

Useful examples:

- route reveal;
- map focus transition;
- timeline ↔ pin selection;
- Inspector slide;
- subtle image motion.

Avoid decorative animation with no product meaning.

Reduced-motion preference must remain supported.

---

## 15. Static Prototype Gate

Major visual redesigns must not be migrated directly into production.

Required workflow:

Reference
→ Design analysis
→ Static prototype
→ Desktop/mobile screenshots
→ Human visual acceptance
→ Production migration.

A prototype may use synthetic:

- maps;
- routes;
- weather;
- scores;
- POIs.

It must clearly identify them as demo data.

---

## 16. Silhouette Check

A major redesign must change composition, not only decoration.

If old and new pages remain nearly identical after:

- removing color;
- hiding text;
- viewing only large layout blocks;

then the redesign is probably only a retheme.

---

## 17. Production Migration

When a static design is approved:

Do not rewrite proven business logic unnecessarily.

Migrate the visual shell around existing:

- API contracts;
- permissions;
- version handling;
- diagnostics;
- provider boundaries;
- persistence;
- error handling.

Visual redesign is not permission to regress functionality.

---

## 18. SmartTrip Anti-patterns

Avoid:

- generic SaaS dashboard styling;
- admin-panel aesthetics;
- equal-width card grids by default;
- card nesting without hierarchy;
- text-heavy feature explanations;
- serif-heavy soft editorial design as the default brand language;
- beige paper as the universal background;
- repeated decorative English eyebrow labels;
- purple-blue generic AI gradients;
- map hidden behind excessive panels;
- giant always-expanded forms;
- mobile layouts created only by stacking desktop columns.

---

## 19. Final Principle

The user should first perceive:

a journey.

Then understand:

the system.

Technology supports the travel experience rather than becoming the visual subject.