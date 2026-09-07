# Creature by Accident

An Android evolution sandbox where you **never design the creature directly**. You change the world; natural selection does the designing.

## Core loop

1. Paint environmental pressures into the habitat.
2. Watch organisms search, feed, drink, compete, reproduce and die.
3. Successful traits are inherited with mutation.
4. Change the environment again and watch the population adapt — or collapse.

The player can alter food, water, temperature, toxins and terrain, but there is intentionally no creature editor.

## v0.1.0 — First playable

The first version is a real-time 2D simulation built with the Android Canvas API and no game-engine dependency.

### Environment tools
- **Food** — creates plant-rich areas.
- **Water** — creates hydration sources and improves natural food regrowth.
- **Heat / Cold** — creates local temperature pressure.
- **Toxin** — damages organisms unless resistance evolves.
- **Rock** — impassable terrain that changes migration routes.
- **Erase** — restores cells toward neutral conditions.

### Evolving genome
Every organism inherits mutable genes controlling body size and proportions, movement speed, sensing radius, metabolism, preferred temperature, toxin resistance, plant-vs-meat diet tendency, aggression, wandering behaviour, colour, appendage count and sensor length.

Organisms spend energy to live and move, must eat and hydrate, may prey on other organisms if their inherited behaviour favours it, reproduce when sufficiently successful, and pass mutated genes to offspring.

### Controls
- Drag in the world to paint with the selected environment tool.
- Tap a tool in the bottom palette to select it.
- **Pause** freezes the simulation while still allowing habitat editing.
- **Speed** cycles 1x / 2x / 4x simulation speed.
- **Seed** adds a few new random organisms without resetting the habitat.
- **Reset** creates a fresh neutral world and founder population.

## Build

GitHub Actions builds the debug APK on every push to `main`, uploads it as an Actions artifact, and publishes the v0.1.0 prerelease asset.

Local build (with Android SDK + Gradle available):

```bash
gradle :app:assembleDebug
```

## Design direction

Future versions should deepen the same premise instead of turning into a conventional creature editor: seasons, terrain elevation, flowing water, sexual selection, eggs/nests, family trees, speciation tracking, articulated body-part physics, procedural plants, disasters, saveable worlds and a fossil/history view.
