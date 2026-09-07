package com.pixel375.creaturebyaccident;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class SimulationView extends View {
    private static final int COLS = 32;
    private static final int ROWS = 44;
    private static final int MAX_CREATURES = 180;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final Cell[][] cells = new Cell[COLS][ROWS];
    private final List<Creature> creatures = new ArrayList<>();
    private final List<Creature> newborns = new ArrayList<>();

    private Tool selectedTool = Tool.FOOD;
    private boolean paused = false;
    private int speedIndex = 0;
    private final float[] speedValues = {1f, 2f, 4f};
    private long lastFrameNs = 0L;
    private float worldTop;
    private float worldBottom;
    private float cellW;
    private float cellH;
    private int peakGeneration = 0;
    private long births = 0;
    private long deaths = 0;
    private boolean initialized = false;

    public SimulationView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(8, 13, 10));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int x = 0; x < COLS; x++) {
            for (int y = 0; y < ROWS; y++) {
                cells[x][y] = new Cell();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        worldTop = dp(92);
        worldBottom = h - dp(158);
        cellW = w / (float) COLS;
        cellH = Math.max(1f, (worldBottom - worldTop) / ROWS);
        if (!initialized && w > 0 && worldBottom > worldTop) {
            resetWorld();
            initialized = true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        if (lastFrameNs == 0L) lastFrameNs = now;
        float dt = Math.min(0.04f, (now - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = now;

        if (!paused && initialized) {
            updateWorld(dt * speedValues[speedIndex]);
        }

        drawWorld(canvas);
        drawCreatures(canvas);
        drawHud(canvas);
        drawToolbar(canvas);
        postInvalidateOnAnimation();
    }

    private void updateWorld(float dt) {
        for (int x = 0; x < COLS; x++) {
            for (int y = 0; y < ROWS; y++) {
                Cell c = cells[x][y];
                if (!c.rock) {
                    float fertility = 0.25f + c.water * 0.75f;
                    c.food = clamp(c.food + dt * 0.0045f * fertility, 0f, 1f);
                    c.toxin = Math.max(0f, c.toxin - dt * 0.00035f);
                }
            }
        }

        newborns.clear();
        for (Creature c : creatures) {
            if (!c.alive) continue;
            updateCreature(c, dt);
        }
        if (!newborns.isEmpty()) {
            creatures.addAll(newborns);
            births += newborns.size();
        }

        Iterator<Creature> it = creatures.iterator();
        while (it.hasNext()) {
            Creature c = it.next();
            if (!c.alive || c.energy <= 0f || c.hydration <= 0f || c.age > 150f) {
                Cell cell = cellAt(c.x, c.y);
                if (cell != null && !cell.rock) cell.food = clamp(cell.food + 0.08f, 0f, 1f);
                it.remove();
                deaths++;
            }
        }
    }

    private void updateCreature(Creature c, float dt) {
        Genome g = c.g;
        c.age += dt;
        c.reproCooldown -= dt;
        c.thinkTimer -= dt;

        Cell here = cellAt(c.x, c.y);
        if (here == null) return;

        float activityCost = 0.55f + g.speed * 0.75f + g.size * 0.55f;
        float defenseCost = g.armor * 0.35f + g.toxinResistance * 0.25f;
        c.energy -= dt * (0.75f + activityCost + defenseCost);
        c.hydration -= dt * (0.7f + g.size * 0.45f + Math.max(0f, here.temp) * 0.45f);

        float temperatureStress = Math.abs(here.temp - g.tempPreference);
        c.energy -= dt * temperatureStress * 3.7f;
        c.energy -= dt * here.toxin * (1f - g.toxinResistance) * 8.0f;

        if (here.water > 0.02f) {
            c.hydration = clamp(c.hydration + dt * (13f + here.water * 26f), 0f, 100f);
        }

        float herbivore = g.diet;
        if (here.food > 0.005f && herbivore > 0.08f) {
            float bite = Math.min(here.food, dt * (0.08f + 0.18f * g.size));
            here.food -= bite;
            c.energy = Math.min(210f, c.energy + bite * (62f * herbivore));
        }

        Creature prey = null;
        float carnivore = 1f - g.diet;
        if (carnivore > 0.45f && c.energy < 155f) {
            prey = nearestPrey(c, visionRadius(c));
            if (prey != null) {
                float dx = prey.x - c.x;
                float dy = prey.y - c.y;
                float dist2 = dx * dx + dy * dy;
                float contact = creatureRadius(c) + creatureRadius(prey) + dp(2);
                if (dist2 < contact * contact) {
                    float damage = dt * (10f + g.aggression * 24f) * (1f - prey.g.armor * 0.68f);
                    prey.energy -= damage;
                    c.energy = Math.min(210f, c.energy + damage * (0.45f + carnivore * 0.32f));
                    if (prey.energy <= 0f) prey.alive = false;
                }
            }
        }

        if (c.thinkTimer <= 0f) {
            if (prey != null) {
                c.targetX = prey.x;
                c.targetY = prey.y;
            } else {
                chooseTarget(c);
            }
            c.thinkTimer = 0.12f + random.nextFloat() * (0.22f + g.wander * 0.18f);
        }

        float dx = c.targetX - c.x;
        float dy = c.targetY - c.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            float a = random.nextFloat() * (float) (Math.PI * 2.0);
            dx = (float) Math.cos(a);
            dy = (float) Math.sin(a);
            len = 1f;
        }
        dx /= len;
        dy /= len;

        float jitter = (random.nextFloat() - 0.5f) * g.wander * 0.38f;
        float ca = (float) Math.cos(jitter);
        float sa = (float) Math.sin(jitter);
        float ndx = dx * ca - dy * sa;
        float ndy = dx * sa + dy * ca;

        float desiredSpeed = getWidth() * (0.022f + g.speed * 0.072f);
        float smooth = clamp(dt * (3.0f + g.aggression * 2f), 0f, 1f);
        c.vx += (ndx * desiredSpeed - c.vx) * smooth;
        c.vy += (ndy * desiredSpeed - c.vy) * smooth;

        float nx = c.x + c.vx * dt;
        float ny = c.y + c.vy * dt;
        Cell next = cellAt(nx, ny);
        if (next == null || next.rock) {
            c.vx *= -0.55f;
            c.vy *= -0.55f;
            float a = random.nextFloat() * (float) (Math.PI * 2.0);
            c.targetX = c.x + (float) Math.cos(a) * visionRadius(c);
            c.targetY = c.y + (float) Math.sin(a) * visionRadius(c);
        } else {
            c.x = nx;
            c.y = ny;
        }

        float r = creatureRadius(c);
        if (c.x < r) { c.x = r; c.vx = Math.abs(c.vx); }
        if (c.x > getWidth() - r) { c.x = getWidth() - r; c.vx = -Math.abs(c.vx); }
        if (c.y < worldTop + r) { c.y = worldTop + r; c.vy = Math.abs(c.vy); }
        if (c.y > worldBottom - r) { c.y = worldBottom - r; c.vy = -Math.abs(c.vy); }

        if (c.energy > 165f && c.hydration > 58f && c.age > 8f && c.reproCooldown <= 0f
                && creatures.size() + newborns.size() < MAX_CREATURES) {
            c.energy -= 68f;
            c.hydration -= 20f;
            c.reproCooldown = 8f + random.nextFloat() * 6f;
            Genome childGenome = c.g.mutated(random);
            Creature child = new Creature(childGenome, c.generation + 1);
            child.x = clamp(c.x + (random.nextFloat() - 0.5f) * dp(18), dp(4), getWidth() - dp(4));
            child.y = clamp(c.y + (random.nextFloat() - 0.5f) * dp(18), worldTop + dp(4), worldBottom - dp(4));
            child.energy = 76f;
            child.hydration = 72f;
            child.targetX = child.x;
            child.targetY = child.y;
            newborns.add(child);
            peakGeneration = Math.max(peakGeneration, child.generation);
        }
    }

    private Creature nearestPrey(Creature hunter, float range) {
        Creature best = null;
        float bestD2 = range * range;
        for (Creature other : creatures) {
            if (other == hunter || !other.alive) continue;
            if (other.g.size > hunter.g.size + 0.30f && hunter.g.aggression < 0.75f) continue;
            float dx = other.x - hunter.x;
            float dy = other.y - hunter.y;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = other;
            }
        }
        return best;
    }

    private void chooseTarget(Creature c) {
        float range = visionRadius(c);
        float bestScore = -9999f;
        float bestX = c.x;
        float bestY = c.y;

        for (int i = 0; i < 12; i++) {
            float angle = (float) (i * Math.PI * 2.0 / 12.0) + (random.nextFloat() - 0.5f) * 0.3f;
            float distance = range * (0.35f + random.nextFloat() * 0.65f);
            float sx = clamp(c.x + (float) Math.cos(angle) * distance, 1f, getWidth() - 1f);
            float sy = clamp(c.y + (float) Math.sin(angle) * distance, worldTop + 1f, worldBottom - 1f);
            Cell cell = cellAt(sx, sy);
            if (cell == null || cell.rock) continue;

            float thirst = 1f - c.hydration / 100f;
            float hunger = clamp((145f - c.energy) / 100f, 0f, 1f);
            float score = cell.food * (26f * c.g.diet) * (0.35f + hunger)
                    + cell.water * 28f * thirst
                    - Math.abs(cell.temp - c.g.tempPreference) * 16f
                    - cell.toxin * (1f - c.g.toxinResistance) * 34f
                    + random.nextFloat() * (2f + c.g.wander * 7f);
            if (score > bestScore) {
                bestScore = score;
                bestX = sx;
                bestY = sy;
            }
        }
        c.targetX = bestX;
        c.targetY = bestY;
    }

    private void drawWorld(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawColor(Color.rgb(8, 13, 10));
        paint.setColor(Color.rgb(28, 34, 26));
        canvas.drawRect(0, worldTop, getWidth(), worldBottom, paint);

        for (int x = 0; x < COLS; x++) {
            for (int y = 0; y < ROWS; y++) {
                Cell c = cells[x][y];
                float left = x * cellW;
                float top = worldTop + y * cellH;
                float right = left + cellW + 1f;
                float bottom = top + cellH + 1f;

                int baseR = 30 + (int) (c.food * 12);
                int baseG = 37 + (int) (c.food * 75);
                int baseB = 28 + (int) (c.food * 7);
                paint.setColor(Color.rgb(clampInt(baseR, 0, 255), clampInt(baseG, 0, 255), clampInt(baseB, 0, 255)));
                canvas.drawRect(left, top, right, bottom, paint);

                if (c.water > 0.02f) {
                    paint.setColor(Color.argb((int) (70 + c.water * 120), 40, 130, 205));
                    canvas.drawRect(left, top, right, bottom, paint);
                }
                if (c.temp > 0.03f) {
                    paint.setColor(Color.argb((int) (Math.abs(c.temp) * 125), 225, 83, 48));
                    canvas.drawRect(left, top, right, bottom, paint);
                } else if (c.temp < -0.03f) {
                    paint.setColor(Color.argb((int) (Math.abs(c.temp) * 125), 74, 162, 235));
                    canvas.drawRect(left, top, right, bottom, paint);
                }
                if (c.toxin > 0.02f) {
                    paint.setColor(Color.argb((int) (50 + c.toxin * 130), 170, 70, 194));
                    canvas.drawRect(left, top, right, bottom, paint);
                }
                if (c.rock) {
                    paint.setColor(Color.rgb(78, 82, 76));
                    canvas.drawRect(left, top, right, bottom, paint);
                    stroke.setColor(Color.rgb(96, 101, 94));
                    stroke.setStrokeWidth(dp(0.8f));
                    canvas.drawLine(left, top, right, bottom, stroke);
                }
            }
        }

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(Color.rgb(55, 66, 54));
        canvas.drawRect(0, worldTop, getWidth(), worldBottom, stroke);
    }

    private void drawCreatures(Canvas canvas) {
        for (Creature c : creatures) drawCreature(canvas, c);
        if (creatures.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(205, 8, 13, 10));
            canvas.drawRoundRect(dp(28), (worldTop + worldBottom) / 2f - dp(58), getWidth() - dp(28),
                    (worldTop + worldBottom) / 2f + dp(58), dp(18), dp(18), paint);
            drawTextCentered(canvas, "EXTINCTION", (worldTop + worldBottom) / 2f - dp(16), dp(22), Color.WHITE, true);
            drawTextCentered(canvas, "Reshape the habitat, then tap SEED", (worldTop + worldBottom) / 2f + dp(18), dp(13), Color.rgb(185, 198, 186), false);
        }
    }

    private void drawCreature(Canvas canvas, Creature c) {
        float r = creatureRadius(c);
        float aspect = 0.70f + c.g.bodyAspect * 1.45f;
        float bodyLength = r * (1.2f + aspect);
        float bodyWidth = r * (1.35f - c.g.bodyAspect * 0.28f);
        float angle = (float) Math.toDegrees(Math.atan2(c.vy, c.vx));
        int bodyColor = Color.HSVToColor(new float[]{c.g.hue, 0.62f, 0.92f});
        int darkColor = Color.HSVToColor(new float[]{c.g.hue, 0.72f, 0.48f});

        canvas.save();
        canvas.translate(c.x, c.y);
        canvas.rotate(angle);

        int legPairs = Math.round(c.g.legs * 4f);
        stroke.setStrokeWidth(Math.max(dp(1f), r * 0.14f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(darkColor);
        for (int i = 0; i < legPairs; i++) {
            float t = legPairs <= 1 ? 0f : (i / (float) (legPairs - 1) - 0.5f);
            float lx = t * bodyLength * 0.65f;
            float spread = bodyWidth * (0.95f + c.g.speed * 0.5f);
            canvas.drawLine(lx, bodyWidth * 0.25f, lx - r * 0.28f, spread, stroke);
            canvas.drawLine(lx, -bodyWidth * 0.25f, lx - r * 0.28f, -spread, stroke);
        }

        if (c.g.armor > 0.18f) {
            stroke.setStrokeWidth(Math.max(dp(0.8f), r * 0.10f));
            stroke.setColor(darkColor);
            int spikes = 2 + Math.round(c.g.armor * 4f);
            for (int i = 0; i < spikes; i++) {
                float px = -bodyLength * 0.35f + i * (bodyLength * 0.7f / Math.max(1, spikes - 1));
                canvas.drawLine(px, -bodyWidth * 0.42f, px - r * 0.08f, -bodyWidth * (0.70f + c.g.armor * 0.55f), stroke);
            }
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        RectF body = new RectF(-bodyLength * 0.52f, -bodyWidth * 0.50f, bodyLength * 0.52f, bodyWidth * 0.50f);
        canvas.drawOval(body, paint);

        paint.setColor(Color.argb(95, 255, 255, 255));
        float spot = Math.max(dp(0.7f), r * 0.18f);
        canvas.drawCircle(-bodyLength * 0.12f, bodyWidth * 0.12f, spot, paint);
        if (c.g.diet < 0.45f) canvas.drawCircle(-bodyLength * 0.28f, -bodyWidth * 0.12f, spot * 0.8f, paint);

        float sensorLen = r * (0.5f + c.g.sensor * 1.8f);
        stroke.setStrokeWidth(Math.max(dp(0.7f), r * 0.08f));
        stroke.setColor(darkColor);
        float headX = bodyLength * 0.46f;
        canvas.drawLine(headX, -bodyWidth * 0.17f, headX + sensorLen, -bodyWidth * 0.52f, stroke);
        canvas.drawLine(headX, bodyWidth * 0.17f, headX + sensorLen, bodyWidth * 0.52f, stroke);

        paint.setColor(Color.WHITE);
        float eyeR = Math.max(dp(0.9f), r * 0.14f);
        canvas.drawCircle(bodyLength * 0.33f, -bodyWidth * 0.20f, eyeR, paint);
        canvas.drawCircle(bodyLength * 0.33f, bodyWidth * 0.20f, eyeR, paint);
        paint.setColor(Color.rgb(18, 22, 18));
        canvas.drawCircle(bodyLength * 0.36f, -bodyWidth * 0.20f, eyeR * 0.48f, paint);
        canvas.drawCircle(bodyLength * 0.36f, bodyWidth * 0.20f, eyeR * 0.48f, paint);

        canvas.restore();
    }

    private void drawHud(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(12, 19, 14));
        canvas.drawRect(0, 0, getWidth(), worldTop, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(18));
        paint.setColor(Color.rgb(237, 244, 237));
        canvas.drawText("Creature by Accident", dp(12), dp(23), paint);

        int species = estimateSpecies();
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(dp(11.5f));
        paint.setColor(Color.rgb(157, 176, 160));
        String stats = String.format(Locale.US, "POP %d   SPECIES %d   GEN %d   BIRTHS %d", creatures.size(), species, peakGeneration, births);
        canvas.drawText(stats, dp(12), dp(42), paint);

        float y = dp(50);
        float gap = dp(6);
        float margin = dp(10);
        float buttonW = (getWidth() - margin * 2 - gap * 3) / 4f;
        String[] labels = {paused ? "PLAY" : "PAUSE", trimSpeed(speedValues[speedIndex]) + "× SPEED", "SEED", "RESET"};
        for (int i = 0; i < 4; i++) {
            RectF rect = new RectF(margin + i * (buttonW + gap), y, margin + i * (buttonW + gap) + buttonW, y + dp(32));
            paint.setColor(i == 0 && paused ? Color.rgb(58, 104, 68) : Color.rgb(31, 43, 33));
            canvas.drawRoundRect(rect, dp(8), dp(8), paint);
            drawTextCenteredInRect(canvas, labels[i], rect, dp(10.5f), Color.rgb(220, 230, 221), true);
        }
    }

    private void drawToolbar(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(11, 18, 13));
        canvas.drawRect(0, worldBottom, getWidth(), getHeight(), paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(12));
        paint.setColor(Color.rgb(224, 234, 225));
        canvas.drawText("SHAPE THE WORLD  •  " + selectedTool.label.toUpperCase(Locale.US), dp(12), worldBottom + dp(20), paint);

        Tool[] tools = Tool.values();
        float margin = dp(8);
        float gap = dp(5);
        float buttonW = (getWidth() - margin * 2 - gap * 3) / 4f;
        float buttonH = dp(49);
        float startY = worldBottom + dp(29);

        for (int i = 0; i < tools.length; i++) {
            int row = i / 4;
            int col = i % 4;
            RectF rect = new RectF(margin + col * (buttonW + gap), startY + row * (buttonH + gap),
                    margin + col * (buttonW + gap) + buttonW, startY + row * (buttonH + gap) + buttonH);
            Tool tool = tools[i];
            int color = tool.color;
            if (tool == selectedTool) {
                paint.setColor(withAlpha(color, 210));
            } else {
                paint.setColor(Color.rgb(28, 37, 30));
            }
            canvas.drawRoundRect(rect, dp(9), dp(9), paint);

            paint.setColor(tool == selectedTool ? Color.WHITE : withAlpha(color, 235));
            canvas.drawCircle(rect.left + dp(15), rect.centerY(), dp(5), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(10.5f));
            paint.setColor(Color.rgb(226, 234, 227));
            canvas.drawText(tool.label, rect.left + dp(25), rect.centerY() + dp(3.8f), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                && y >= worldTop && y <= worldBottom) {
            paintEnvironment(x, y);
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN && y < worldTop) {
            handleHudTap(x, y);
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN && y > worldBottom) {
            handleToolbarTap(x, y);
            return true;
        }

        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleHudTap(float x, float y) {
        if (y < dp(50) || y > dp(84)) return;
        float margin = dp(10);
        float gap = dp(6);
        float buttonW = (getWidth() - margin * 2 - gap * 3) / 4f;
        for (int i = 0; i < 4; i++) {
            float left = margin + i * (buttonW + gap);
            if (x >= left && x <= left + buttonW) {
                if (i == 0) paused = !paused;
                if (i == 1) speedIndex = (speedIndex + 1) % speedValues.length;
                if (i == 2) seedCreatures(8);
                if (i == 3) resetWorld();
                break;
            }
        }
    }

    private void handleToolbarTap(float x, float y) {
        float margin = dp(8);
        float gap = dp(5);
        float buttonW = (getWidth() - margin * 2 - gap * 3) / 4f;
        float buttonH = dp(49);
        float startY = worldBottom + dp(29);
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            int row = i / 4;
            int col = i % 4;
            RectF rect = new RectF(margin + col * (buttonW + gap), startY + row * (buttonH + gap),
                    margin + col * (buttonW + gap) + buttonW, startY + row * (buttonH + gap) + buttonH);
            if (rect.contains(x, y)) {
                selectedTool = tools[i];
                break;
            }
        }
    }

    private void paintEnvironment(float px, float py) {
        int cx = clampInt((int) (px / cellW), 0, COLS - 1);
        int cy = clampInt((int) ((py - worldTop) / cellH), 0, ROWS - 1);
        int radius = selectedTool == Tool.ROCK ? 1 : 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || x >= COLS || y < 0 || y >= ROWS) continue;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > radius + 0.25f) continue;
                float strength = 1f - dist / (radius + 0.8f);
                Cell c = cells[x][y];
                switch (selectedTool) {
                    case FOOD:
                        c.rock = false;
                        c.food = clamp(c.food + 0.32f * strength, 0f, 1f);
                        break;
                    case WATER:
                        c.rock = false;
                        c.water = clamp(c.water + 0.28f * strength, 0f, 1f);
                        break;
                    case HEAT:
                        c.temp = clamp(c.temp + 0.18f * strength, -1f, 1f);
                        break;
                    case COLD:
                        c.temp = clamp(c.temp - 0.18f * strength, -1f, 1f);
                        break;
                    case TOXIN:
                        c.toxin = clamp(c.toxin + 0.22f * strength, 0f, 1f);
                        break;
                    case ROCK:
                        c.rock = true;
                        c.food = 0f;
                        c.water = 0f;
                        c.toxin = 0f;
                        break;
                    case ERASE:
                        c.rock = false;
                        c.food *= 0.35f;
                        c.water *= 0.35f;
                        c.temp *= 0.35f;
                        c.toxin *= 0.20f;
                        break;
                }
            }
        }
    }

    private void resetWorld() {
        births = 0;
        deaths = 0;
        peakGeneration = 0;
        for (int x = 0; x < COLS; x++) {
            for (int y = 0; y < ROWS; y++) cells[x][y].reset();
        }

        for (int i = 0; i < 8; i++) {
            int cx = random.nextInt(COLS);
            int cy = random.nextInt(ROWS);
            int radius = 2 + random.nextInt(4);
            for (int x = Math.max(0, cx - radius); x <= Math.min(COLS - 1, cx + radius); x++) {
                for (int y = Math.max(0, cy - radius); y <= Math.min(ROWS - 1, cy + radius); y++) {
                    float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                    if (d <= radius) {
                        cells[x][y].food = Math.max(cells[x][y].food, 0.45f + random.nextFloat() * 0.45f);
                        if (i % 2 == 0 && d < radius * 0.55f) cells[x][y].water = 0.55f + random.nextFloat() * 0.40f;
                    }
                }
            }
        }

        creatures.clear();
        seedCreatures(34);
        paused = false;
        lastFrameNs = System.nanoTime();
    }

    private void seedCreatures(int count) {
        if (getWidth() <= 0 || worldBottom <= worldTop) return;
        int available = MAX_CREATURES - creatures.size();
        count = Math.min(count, available);
        for (int i = 0; i < count; i++) {
            Genome g = Genome.randomFounder(random);
            Creature c = new Creature(g, 0);
            for (int attempt = 0; attempt < 25; attempt++) {
                c.x = dp(8) + random.nextFloat() * Math.max(1f, getWidth() - dp(16));
                c.y = worldTop + dp(8) + random.nextFloat() * Math.max(1f, worldBottom - worldTop - dp(16));
                Cell cell = cellAt(c.x, c.y);
                if (cell != null && !cell.rock) break;
            }
            c.targetX = c.x;
            c.targetY = c.y;
            c.energy = 96f + random.nextFloat() * 26f;
            c.hydration = 70f + random.nextFloat() * 25f;
            creatures.add(c);
        }
    }

    private Cell cellAt(float px, float py) {
        if (px < 0f || px >= getWidth() || py < worldTop || py >= worldBottom) return null;
        int x = clampInt((int) (px / cellW), 0, COLS - 1);
        int y = clampInt((int) ((py - worldTop) / cellH), 0, ROWS - 1);
        return cells[x][y];
    }

    private float creatureRadius(Creature c) {
        return dp(3.3f + c.g.size * 4.7f);
    }

    private float visionRadius(Creature c) {
        return getWidth() * (0.035f + c.g.vision * 0.13f);
    }

    private int estimateSpecies() {
        HashSet<Integer> keys = new HashSet<>();
        for (Creature c : creatures) {
            int hue = ((int) c.g.hue / 45) & 7;
            int size = c.g.size > 0.55f ? 1 : 0;
            int diet = c.g.diet > 0.62f ? 2 : (c.g.diet < 0.38f ? 0 : 1);
            int legs = Math.round(c.g.legs * 3f);
            int key = hue + size * 8 + diet * 16 + legs * 48;
            keys.add(key);
        }
        return keys.size();
    }

    private void drawTextCentered(Canvas canvas, String text, float y, float size, int color, boolean bold) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        paint.setTextSize(size);
        paint.setColor(color);
        canvas.drawText(text, getWidth() / 2f, y, paint);
    }

    private void drawTextCenteredInRect(Canvas canvas, String text, RectF rect, float size, int color, boolean bold) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        paint.setTextSize(size);
        paint.setColor(color);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, rect.centerX(), baseline, paint);
    }

    private String trimSpeed(float value) {
        return value == Math.round(value) ? Integer.toString(Math.round(value)) : Float.toString(value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private enum Tool {
        FOOD("Food", Color.rgb(88, 190, 94)),
        WATER("Water", Color.rgb(65, 154, 232)),
        HEAT("Heat", Color.rgb(235, 101, 63)),
        COLD("Cold", Color.rgb(100, 191, 241)),
        TOXIN("Toxin", Color.rgb(184, 86, 208)),
        ROCK("Rock", Color.rgb(143, 147, 140)),
        ERASE("Erase", Color.rgb(210, 211, 205));

        final String label;
        final int color;

        Tool(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private static class Cell {
        float food;
        float water;
        float temp;
        float toxin;
        boolean rock;

        void reset() {
            food = 0.025f;
            water = 0f;
            temp = 0f;
            toxin = 0f;
            rock = false;
        }
    }

    private static class Creature {
        final Genome g;
        final int generation;
        float x;
        float y;
        float vx;
        float vy;
        float targetX;
        float targetY;
        float energy = 100f;
        float hydration = 85f;
        float age = 0f;
        float thinkTimer = 0f;
        float reproCooldown = 5f;
        boolean alive = true;

        Creature(Genome genome, int generation) {
            this.g = genome;
            this.generation = generation;
        }
    }

    private static class Genome {
        float size;
        float speed;
        float vision;
        float tempPreference;
        float toxinResistance;
        float diet;
        float aggression;
        float wander;
        float hue;
        float bodyAspect;
        float legs;
        float sensor;
        float armor;

        static Genome randomFounder(Random r) {
            Genome g = new Genome();
            g.size = between(r, 0.25f, 0.72f);
            g.speed = between(r, 0.25f, 0.72f);
            g.vision = between(r, 0.20f, 0.75f);
            g.tempPreference = between(r, -0.28f, 0.28f);
            g.toxinResistance = between(r, 0.02f, 0.28f);
            g.diet = between(r, 0.48f, 0.92f);
            g.aggression = between(r, 0.10f, 0.62f);
            g.wander = between(r, 0.18f, 0.78f);
            g.hue = r.nextFloat() * 360f;
            g.bodyAspect = between(r, 0.12f, 0.88f);
            g.legs = between(r, 0.10f, 0.82f);
            g.sensor = between(r, 0.12f, 0.78f);
            g.armor = between(r, 0.02f, 0.32f);
            return g;
        }

        Genome mutated(Random r) {
            Genome g = new Genome();
            float m = 0.075f;
            g.size = mutate01(size, r, m);
            g.speed = mutate01(speed, r, m);
            g.vision = mutate01(vision, r, m);
            g.tempPreference = clamp(tempPreference + gaussianish(r) * 0.085f, -1f, 1f);
            g.toxinResistance = mutate01(toxinResistance, r, m);
            g.diet = mutate01(diet, r, m * 1.15f);
            g.aggression = mutate01(aggression, r, m);
            g.wander = mutate01(wander, r, m);
            g.hue = (hue + gaussianish(r) * 16f + 360f) % 360f;
            g.bodyAspect = mutate01(bodyAspect, r, m);
            g.legs = mutate01(legs, r, m);
            g.sensor = mutate01(sensor, r, m);
            g.armor = mutate01(armor, r, m);
            if (r.nextFloat() < 0.025f) {
                int gene = r.nextInt(8);
                float jump = gaussianish(r) * 0.22f;
                if (gene == 0) g.size = clamp(g.size + jump, 0f, 1f);
                if (gene == 1) g.speed = clamp(g.speed + jump, 0f, 1f);
                if (gene == 2) g.vision = clamp(g.vision + jump, 0f, 1f);
                if (gene == 3) g.diet = clamp(g.diet + jump, 0f, 1f);
                if (gene == 4) g.toxinResistance = clamp(g.toxinResistance + jump, 0f, 1f);
                if (gene == 5) g.armor = clamp(g.armor + jump, 0f, 1f);
                if (gene == 6) g.bodyAspect = clamp(g.bodyAspect + jump, 0f, 1f);
                if (gene == 7) g.legs = clamp(g.legs + jump, 0f, 1f);
            }
            return g;
        }

        private static float mutate01(float value, Random r, float amount) {
            return clamp(value + gaussianish(r) * amount, 0f, 1f);
        }

        private static float gaussianish(Random r) {
            return (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f) / 1.5f;
        }

        private static float between(Random r, float min, float max) {
            return min + r.nextFloat() * (max - min);
        }
    }
}
