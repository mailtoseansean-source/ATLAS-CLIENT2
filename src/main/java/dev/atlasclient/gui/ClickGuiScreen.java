package dev.atlasclient.gui;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.gui.Animator;
import dev.atlasclient.core.gui.GuiModel;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Setting;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ChoiceSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.KeybindSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.core.setting.Settings.StringSetting;
import dev.atlasclient.gui.UiKit.Palette;
import dev.atlasclient.module.client.GuiSettingsModule;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Dark ClickGUI. Opens in the simple view: one page with the main features (X-Ray, ESP, Freecam,
 * Auto Mine, Base Finder, Debris Finder, Flight), each a switch plus a few options. "All settings"
 * switches to the full view: category sidebar, search bar, module cards with animated toggle
 * switches, and expandable settings (switches, sliders, dropdowns, color picker, keybind capture,
 * buttons, text). Layout is recomputed every frame (responsive to window size). State lives in
 * {@link GuiModel}.
 */
public final class ClickGuiScreen extends Screen {
    private static final int SIDEBAR = 112;
    private static final int TOP = 30;
    private static final int CARD_H = 34;
    /** Main-menu cards are compact so every feature fits on one screen without scrolling. */
    private static final int SIMPLE_H = 28;
    private static final int ROW_H = 18;

    private final GuiModel model;
    private final Animator anim = new Animator();
    private final UiKit.Hits hits = new UiKit.Hits();
    private NumberSetting draggingSlider;
    private int sliderX, sliderW;
    private ColorSetting pickerFor;
    // Searchable list for choice settings (e.g. the AutoBuilder schematic).
    private ChoiceSetting choiceFor;
    private List<String> choiceOptions = List.of();
    private final StringBuilder choiceQuery = new StringBuilder();
    private int choiceScroll;
    private int choiceIndex;
    private int choiceX, choiceY, choiceW, choiceH, choiceListY;
    private final List<String> choiceRows = new java.util.ArrayList<>();
    private int pickerX, pickerY;
    private float[] pickerHsv = new float[3];
    private int pickerAlpha = 255;
    private int pickerDrag; // 0 none, 1 square, 2 hue, 3 alpha
    private StringSetting editing;
    private final StringBuilder editBuffer = new StringBuilder();
    private String tooltip;
    /** Simple view (default) or the full category view. Saved in the UI preferences. */
    private boolean simple = true;
    private String simpleExpanded;

    /** One option row under a simple-view card: a setting, or a switch for a whole module. */
    private record Option(String label, Setting<?> setting, Module module, String owner) { }

    /** One card of the simple view. */
    private record SimpleEntry(String id, String title, String status, boolean on, Runnable toggle, String key, List<Option> options) { }

    public ClickGuiScreen() {
        super(Text.translatable("screen.atlasclient.gui"));
        this.model = Atlas.get().gui;
        this.model.loadPrefs(Atlas.get().config.uiPrefs());
        var ui = Atlas.get().config.uiPrefs();
        this.simple = !ui.has("simple") || ui.get("simple").getAsBoolean();
        this.anim.snap("open", 0.0);
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        model.savePrefs(Atlas.get().config.uiPrefs());
        Atlas.get().config.uiPrefs().addProperty("simple", simple);
        Atlas.get().config.saveClientFile();
        Atlas.get().config.markDirty();
        Atlas.get().config.flushIfDirty();
        super.removed();
    }

    private double guiScale() {
        GuiSettingsModule s = Atlas.get().modules.get(GuiSettingsModule.class);
        return s == null ? 1.0 : s.animationSpeed.get();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        Palette pal = Palette.current();
        anim.setSpeed(14 * guiScale());
        anim.frame(System.nanoTime());
        hits.clear();
        tooltip = null;

        double open = anim.approach("open", 1.0);
        int yOff = (int) ((1 - open) * 12);
        if (simple) {
            renderSimple(ctx, pal, mouseX, mouseY, open, yOff);
            drawOverlays(ctx, pal, mouseX, mouseY);
            return;
        }
        int w = Math.min(width - 24, 620), h = Math.min(height - 24, 380);
        int x = (width - w) / 2, y = (height - h) / 2;
        y += yOff;

        UiKit.roundRect(ctx, x, y, w, h, 8, UiKit.withAlpha(pal.panel(), open));
        UiKit.roundRect(ctx, x, y, SIDEBAR, h, 8, UiKit.withAlpha(pal.background(), open));
        UiKit.textShadow(ctx, "Atlas Client", x + 12, y + 11, UiKit.withAlpha(pal.accent(), open));

        // Sidebar: "Active" (everything switched on) and the categories
        int rows = Category.values().length + 1;
        int step = Math.max(16, Math.min(24, (h - TOP - 44) / rows));
        int cy = y + TOP + 4;
        boolean searching = !model.search().isEmpty();
        long activeCount = Atlas.get().modules.all().stream().filter(m -> m.isEnabled() && m.canToggle()).count();
        cy = sidebarRow(ctx, pal, x, cy, step, mouseX, mouseY, "Active (" + activeCount + ")", "cat.active",
                model.activeView() && !searching, () -> { model.clearSearch(); model.selectActive(); });
        for (Category c : Category.values()) {
            final Category cat = c;
            cy = sidebarRow(ctx, pal, x, cy, step, mouseX, mouseY, c.displayName(), "cat." + c,
                    c == model.category() && !model.activeView() && !searching, () -> { model.clearSearch(); model.selectCategory(cat); });
        }
        sidebarRow(ctx, pal, x, y + h - 26, step, mouseX, mouseY, "< Simple view", "cat.simple", false, () -> {
            simple = true;
            model.clearSearch();
            model.focusSearch(false);
        });

        // Search bar
        int sx = x + SIDEBAR + 10, sw = w - SIDEBAR - 20;
        UiKit.roundRect(ctx, sx, y + 6, sw, 18, 5, model.searchFocused() ? pal.cardHover() : pal.card());
        String q = model.search();
        String shown = q.isEmpty() && !model.searchFocused() ? "Search modules and settings..." : q + (model.searchFocused() && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        UiKit.text(ctx, shown, sx + 6, y + 11, q.isEmpty() ? pal.textDim() : pal.text());
        hits.add(sx, y + 6, sw, 18, null, () -> model.focusSearch(true), model::clearSearch);

        // Module list
        int listX = sx, listY = y + TOP + 2, listW = sw, listH = h - TOP - 8;
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int contentY = listY - (int) model.scroll();
        List<Module> mods = model.visibleModules();
        for (Module m : mods) {
            contentY = drawModule(ctx, pal, m, listX, contentY, listW, mouseX, mouseY, listY, listY + listH);
            contentY += 6;
        }
        ctx.disableScissor();
        int contentHeight = contentY + (int) model.scroll() - listY;
        model.setMaxScroll(Math.max(0, contentHeight - listH));
        if (mods.isEmpty()) {
            String empty = !q.isEmpty() ? "No modules match \"" + q + "\""
                    : model.activeView() ? "Nothing is switched on. Pick a category and click a switch." : "No modules in this category";
            UiKit.text(ctx, empty, listX + 8, listY + 10, pal.textDim());
        }

        drawOverlays(ctx, pal, mouseX, mouseY);
    }

    private void drawOverlays(DrawContext ctx, Palette pal, int mouseX, int mouseY) {
        if (choiceFor != null) {
            drawChoice(ctx, pal, mouseX, mouseY);
            return;
        }
        if (pickerFor != null) {
            drawColorPicker(ctx, pal, mouseX, mouseY);
        }
        if (tooltip != null && pickerFor == null) {
            List<String> lines = UiKit.wrap(tooltip, 220);
            int tw = 0;
            for (String l : lines) tw = Math.max(tw, UiKit.font().getWidth(l));
            int tx = Math.min(mouseX + 10, width - tw - 12), ty = mouseY + 12;
            UiKit.roundRect(ctx, tx - 4, ty - 4, tw + 8, lines.size() * 10 + 6, 4, 0xF0101018);
            for (int i = 0; i < lines.size(); i++) UiKit.text(ctx, lines.get(i), tx, ty + i * 10, pal.text());
        }
    }

    // ------------------------------------------------------------------ searchable choice list

    /** Opens the searchable list for a choice setting (the in-game tests use this too). */
    public void openChoice(ChoiceSetting c) {
        choiceFor = c;
        choiceOptions = new java.util.ArrayList<>(c.options()); // read the folder once, not every frame
        choiceQuery.setLength(0);
        choiceScroll = 0;
        choiceIndex = 0;
    }

    /** Options whose name contains every word typed (any order, any case). */
    private List<String> choiceMatches() {
        String[] words = choiceQuery.toString().toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        List<String> out = new java.util.ArrayList<>();
        for (String o : choiceOptions) {
            String lo = o.toLowerCase(java.util.Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!w.isEmpty() && !lo.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(o);
        }
        return out;
    }

    private void pickChoice(String value) {
        choiceFor.set(value);
        choiceFor = null;
        Atlas.get().config.markDirty();
    }

    private static final int CHOICE_ROW = 14;

    private void drawChoice(DrawContext ctx, Palette pal, int mouseX, int mouseY) {
        choiceW = Math.min(360, width - 40);
        choiceH = Math.min(260, height - 40);
        choiceX = (width - choiceW) / 2;
        choiceY = (height - choiceH) / 2;
        ctx.fill(0, 0, width, height, 0x80000000);
        UiKit.roundRect(ctx, choiceX, choiceY, choiceW, choiceH, 8, pal.panel());
        List<String> matches = choiceMatches();
        choiceIndex = Math.max(0, Math.min(choiceIndex, matches.size() - 1));
        UiKit.textShadow(ctx, "Pick " + choiceFor.name().toLowerCase(java.util.Locale.ROOT), choiceX + 10, choiceY + 8, pal.accent());
        String count = matches.size() + " of " + choiceOptions.size();
        UiKit.text(ctx, count, choiceX + choiceW - 10 - UiKit.font().getWidth(count), choiceY + 8, pal.textDim());
        // Search box: always typing into it.
        int sy = choiceY + 22;
        UiKit.roundRect(ctx, choiceX + 8, sy, choiceW - 16, 18, 5, pal.cardHover());
        String q = choiceQuery.toString();
        String cursor = (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
        UiKit.text(ctx, q.isEmpty() ? "Type to search..." + cursor : q + cursor, choiceX + 14, sy + 5, q.isEmpty() ? pal.textDim() : pal.text());
        // List
        int ly = sy + 24, lh = choiceH - (ly - choiceY) - 18;
        int visibleRows = Math.max(1, lh / CHOICE_ROW);
        int maxScroll = Math.max(0, matches.size() - visibleRows);
        if (choiceIndex < choiceScroll) choiceScroll = choiceIndex;
        if (choiceIndex >= choiceScroll + visibleRows) choiceScroll = choiceIndex - visibleRows + 1;
        choiceScroll = Math.max(0, Math.min(choiceScroll, maxScroll));
        if (choiceOptions.isEmpty()) {
            UiKit.text(ctx, "No files found. Put .litematic / .schem files in", choiceX + 12, ly + 4, pal.textDim());
            UiKit.text(ctx, ".minecraft/schematics", choiceX + 12, ly + 16, pal.textDim());
        } else if (matches.isEmpty()) {
            UiKit.text(ctx, "Nothing matches \"" + q + "\"", choiceX + 12, ly + 4, pal.textDim());
        }
        choiceListY = ly;
        choiceRows.clear();
        for (int i = 0; i < visibleRows && choiceScroll + i < matches.size(); i++) {
            int idx = choiceScroll + i;
            String o = matches.get(idx);
            int ry = ly + i * CHOICE_ROW;
            boolean hover = mouseX >= choiceX + 8 && mouseX < choiceX + choiceW - 8 && mouseY >= ry && mouseY < ry + CHOICE_ROW;
            if (hover) choiceIndex = idx;
            if (idx == choiceIndex) ctx.fill(choiceX + 8, ry, choiceX + choiceW - 8, ry + CHOICE_ROW, 0x30FFFFFF);
            boolean current = o.equals(choiceFor.get());
            UiKit.text(ctx, UiKit.ellipsize((current ? "> " : "") + o, choiceW - 28), choiceX + 14, ry + 3, current ? pal.accent() : pal.text());
            choiceRows.add(o);
        }
        UiKit.text(ctx, "Type to filter, Enter or click to pick, Esc to close", choiceX + 10, choiceY + choiceH - 13, pal.textDim());
    }

    // ------------------------------------------------------------------ simple view

    private List<SimpleEntry> simpleEntries() {
        var mods = Atlas.get().modules;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        List<SimpleEntry> out = new java.util.ArrayList<>();

        var mine = mods.get(dev.atlasclient.module.building.AutoMineModule.class);
        out.add(card(mine, "Auto Mine", "Face where you want to dig, then turn it on",
                mine.isEnabled() && mine.paused() ? "Paused" : null,
                opt(mine, "Pause / resume", "Pause / resume"), opt(mine, "Mode", "Mode"), opt(mine, "Tunnel size", "Tunnel size"), opt(mine, "Area width", "Width"),
                opt(mine, "Area length", "Length"), opt(mine, "Area depth", "Depth"), opt(mine, "Pickaxe", "Pickaxe")));

        var xray = mods.get(dev.atlasclient.module.render.XRayModule.class);
        out.add(card(xray, "X-Ray", "See ores through walls",
                opt(xray, "Max distance", "How far (blocks)"), opt(xray, "Diamond", "Diamonds"), opt(xray, "Ancient debris", "Ancient debris"),
                opt(xray, "Gold", "Gold"), opt(xray, "Iron", "Iron"), opt(xray, "Emerald", "Emeralds")));

        List<Module> esp = GuiSettingsModule.espModules();
        int espOn = 0;
        for (Module m : esp) if (m.isEnabled()) espOn++;
        GuiSettingsModule gs = mods.get(GuiSettingsModule.class);
        String[] espLabels = {"Players", "Mobs", "Items", "Chests"};
        List<Option> espOptions = new java.util.ArrayList<>();
        for (int i = 0; i < esp.size(); i++) espOptions.add(new Option(espLabels[i], null, esp.get(i), "ESP"));
        out.add(new SimpleEntry("esp", "ESP", espOn == 0 ? "See players, mobs, items and chests through walls" : espOn + " of 4 on",
                espOn > 0, GuiSettingsModule::toggleEsp, gs.espKey.isBound() ? Keybind.name(gs.espKey.key()) : "", espOptions));

        var bases = mods.get(dev.atlasclient.module.world.BaseFinderModule.class);
        int found = bases.isEnabled() ? bases.visibleSites().size() : 0;
        out.add(card(bases, "Base Finder", "Finds hidden bases and overgrown spots players stayed at",
                bases.isEnabled() ? (found == 0 ? "Looking... nothing found yet" : found + (found == 1 ? " possible base found" : " possible bases found")) : null,
                opt(bases, "Range", "Range"), opt(bases, "Focus on growth", "Focus on growth"), opt(bases, "Open discoveries", "Dashboard")));

        var debris = mods.get(dev.atlasclient.module.world.AncientDebrisFinderModule.class);
        int real = debris.isEnabled() && mc.player != null ? debris.shown(mc.player.getX(), mc.player.getY(), mc.player.getZ()).size() : 0;
        out.add(card(debris, "Debris Finder", "Finds real ancient debris (Nether)",
                debris.isEnabled() ? (dev.atlasclient.util.Env.inNether() ? real + " real debris found" : "Go to the Nether") : null));

        var freecam = mods.get(dev.atlasclient.module.camera.FreecamModule.class);
        Module camSpeed = mods.get(dev.atlasclient.module.camera.CameraSpeedModule.class);
        out.add(card(freecam, "Freecam", "Fly the camera around; your player stays still",
                freecam.isEnabled() ? "Camera is free. Turn off to go back" : null, opt(camSpeed, "Speed", "Speed")));

        var builder = mods.get(dev.atlasclient.module.building.AutoBuilderModule.class);
        out.add(card(builder, "AutoBuilder", "Builds a schematic for you: pick one, then Build here",
                opt(builder, "Schematic", "Schematic (click to search)"), opt(builder, "Build here", "Build here"),
                opt(builder, "Pause / resume", "Pause / resume"), opt(builder, "Cancel", "Stop")));

        var flight = mods.get(dev.atlasclient.module.movement.AuthorizedFlightModule.class);
        out.add(card(flight, "Flight", "Fly in single-player, or where the server allows it", flight.isEnabled() ? "Flying" : null));
        return out;
    }

    private SimpleEntry card(Module m, String title, String offText, Option... options) {
        return card(m, title, offText, null, options);
    }

    /** A module card: plain-language text when off, its status (or {@code onText}) when on. */
    private SimpleEntry card(Module m, String title, String offText, String onText, Option... options) {
        String status = !m.isEnabled() ? offText : onText != null ? onText : m.status().isEmpty() ? "On" : m.status();
        List<Option> list = new java.util.ArrayList<>();
        for (Option o : options) if (o != null) list.add(o);
        return new SimpleEntry(m.name(), title, status, m.isEnabled(), m::toggle,
                m.keybind().isBound() ? Keybind.name(m.keybind().key()) : "", list);
    }

    private static Option opt(Module m, String settingName, String label) {
        Setting<?> s = m.setting(settingName);
        return s == null ? null : new Option(label, s, null, m.name());
    }

    private void renderSimple(DrawContext ctx, Palette pal, int mouseX, int mouseY, double open, int yOff) {
        int w = Math.min(width - 24, 440), h = Math.min(height - 24, 400);
        int x = (width - w) / 2, y = (height - h) / 2 + yOff;
        UiKit.roundRect(ctx, x, y, w, h, 8, UiKit.withAlpha(pal.panel(), open));
        UiKit.textShadow(ctx, "Atlas Client", x + 12, y + 11, UiKit.withAlpha(pal.accent(), open));
        // "All settings" button (top right)
        String all = "More settings >";
        int bw = UiKit.font().getWidth(all) + 14, bx = x + w - bw - 8, by = y + 6;
        boolean bh = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 16;
        UiKit.roundRect(ctx, bx, by, bw, 16, 5, bh ? pal.accent() : pal.card());
        UiKit.text(ctx, all, bx + 7, by + 4, pal.text());
        hits.add(bx, by, bw, 16, "Every module and setting, by category", () -> {
            simple = false;
            model.focusSearch(false);
        }, null);

        int listX = x + 10, listY = y + TOP, listW = w - 20, listH = h - TOP - 22;
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int cy = listY - (int) model.scroll();
        for (SimpleEntry e : simpleEntries()) {
            cy = drawSimpleEntry(ctx, pal, e, listX, cy, listW, mouseX, mouseY, listY, listY + listH);
            cy += 3;
        }
        ctx.disableScissor();
        model.setMaxScroll(Math.max(0, cy + (int) model.scroll() - listY - listH));
        UiKit.text(ctx, "Click a card to turn it on or off. Click > for options.", x + 12, y + h - 15, pal.textDim());
    }

    private int drawSimpleEntry(DrawContext ctx, Palette pal, SimpleEntry e, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + SIMPLE_H && mouseY >= clipTop && mouseY < clipBottom;
        double hv = anim.approach("shover." + e.id(), hover ? 1 : 0);
        UiKit.roundRect(ctx, x, y, w, SIMPLE_H, 6, UiKit.lerpColor(pal.card(), pal.cardHover(), hv));
        UiKit.text(ctx, e.title(), x + 10, y + 5, pal.text());
        if (!e.key().isEmpty()) {
            String k = "key " + e.key();
            UiKit.text(ctx, k, x + 16 + UiKit.font().getWidth(e.title()), y + 5, pal.textDim());
        }
        UiKit.text(ctx, UiKit.ellipsize(e.status(), w - 80), x + 10, y + 16, pal.textDim());
        double on = anim.approach("ssw." + e.id(), e.on() ? 1 : 0);
        int swX = x + w - 50, swY = y + 7;
        UiKit.roundRect(ctx, swX, swY, 28, 14, 7, UiKit.lerpColor(pal.outline(), pal.accent(), on));
        UiKit.roundRect(ctx, swX + 2 + (int) Math.round(on * 14), swY + 2, 10, 10, 5, 0xFFFFFFFF);
        boolean hasOptions = !e.options().isEmpty();
        boolean expanded = e.id().equals(simpleExpanded);
        Runnable expand = () -> simpleExpanded = expanded ? null : e.id();
        if (hasOptions) UiKit.text(ctx, expanded ? "v" : ">", x + w - 14, y + 10, pal.textDim());
        if (y + SIMPLE_H > clipTop && y < clipBottom) {
            hits.add(x, y, w, SIMPLE_H, null, e.toggle(), hasOptions ? expand : null);
            if (hasOptions) hits.add(x + w - 20, y, 20, SIMPLE_H, null, expand, expand);
        }
        int cy = y + SIMPLE_H;
        if (expanded) {
            cy += 2;
            for (Option o : e.options()) {
                if (o.module() != null) {
                    cy = drawModuleSwitchRow(ctx, pal, o.module(), o.label(), x + 8, cy, w - 16, mouseX, mouseY, clipTop, clipBottom);
                } else if (o.setting().isVisible()) {
                    cy = drawSetting(ctx, pal, o.setting(), o.owner(), o.label(), x + 8, cy, w - 16, mouseX, mouseY, clipTop, clipBottom);
                }
            }
            cy += 2;
        }
        return cy;
    }

    /** A settings-style row whose switch turns a whole module on or off (used for the ESP card). */
    private int drawModuleSwitchRow(DrawContext ctx, Palette pal, Module m, String label, int x, int y, int w, int mouseX, int mouseY,
                                    int clipTop, int clipBottom) {
        boolean visible = y + ROW_H > clipTop && y < clipBottom;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H && visible;
        if (hover) {
            ctx.fill(x, y, x + w, y + ROW_H, 0x18FFFFFF);
            tooltip = m.description();
        }
        UiKit.text(ctx, label, x + 4, y + 5, pal.text());
        double on = anim.approach("msw." + m.name(), m.isEnabled() ? 1 : 0);
        UiKit.roundRect(ctx, x + w - 30, y + 3, 24, 12, 6, UiKit.lerpColor(pal.outline(), pal.accent(), on));
        UiKit.roundRect(ctx, x + w - 28 + (int) Math.round(on * 12), y + 5, 8, 8, 4, 0xFFFFFFFF);
        if (visible) hits.add(x, y, w, ROW_H, null, m::toggle, null);
        return y + ROW_H;
    }

    private int sidebarRow(DrawContext ctx, Palette pal, int x, int cy, int step, int mouseX, int mouseY, String label,
                           String animKey, boolean sel, Runnable onClick) {
        int rowH = Math.min(20, step - 2);
        boolean hover = mouseX >= x + 6 && mouseX < x + SIDEBAR - 6 && mouseY >= cy && mouseY < cy + rowH;
        double t = anim.approach(animKey, sel ? 1 : hover ? 0.5 : 0);
        if (t > 0.01) {
            UiKit.roundRect(ctx, x + 6, cy, SIDEBAR - 12, rowH, 5, UiKit.withAlpha(pal.cardHover(), t));
        }
        if (sel) ctx.fill(x + 6, cy + 4, x + 8, cy + rowH - 4, pal.accent());
        UiKit.text(ctx, UiKit.ellipsize(label, SIDEBAR - 26), x + 16, cy + (rowH - 8) / 2, sel ? pal.text() : pal.textDim());
        hits.add(x + 6, cy, SIDEBAR - 12, rowH, null, onClick, null);
        return cy + step;
    }

    private int drawModule(DrawContext ctx, Palette pal, Module m, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CARD_H && mouseY >= clipTop && mouseY < clipBottom;
        double hv = anim.approach("hover." + m.name(), hover ? 1 : 0);
        UiKit.roundRect(ctx, x, y, w, CARD_H, 6, UiKit.lerpColor(pal.card(), pal.cardHover(), hv));
        UiKit.text(ctx, m.name(), x + 10, y + 7, pal.text());
        String status = m.status();
        String desc = status.isEmpty() ? m.description() : status;
        UiKit.text(ctx, UiKit.ellipsize(desc, w - 80), x + 10, y + 20, pal.textDim());
        // toggle switch
        double on = anim.approach("sw." + m.name(), m.isEnabled() ? 1 : 0);
        int swX = x + w - 44, swY = y + 10;
        if (m.canToggle()) {
            UiKit.roundRect(ctx, swX, swY, 28, 14, 7, UiKit.lerpColor(pal.outline(), pal.accent(), on));
            int knob = swX + 2 + (int) Math.round(on * 14);
            UiKit.roundRect(ctx, knob, swY + 2, 10, 10, 5, 0xFFFFFFFF);
        } else {
            UiKit.text(ctx, "always on", swX - 18, swY + 3, pal.textDim());
        }
        boolean expanded = model.expanded() == m;
        UiKit.text(ctx, expanded ? "v" : ">", x + w - 12, y + 13, pal.textDim());
        if (y + CARD_H > clipTop && y < clipBottom) {
            hits.add(x, y, w, CARD_H, m.description(), () -> model.clickModule(m, 0), () -> model.clickModule(m, 1));
            hits.add(x + w - 16, y, 16, CARD_H, null, () -> model.toggleExpanded(m), () -> model.toggleExpanded(m));
            if (hover) tooltip = m.description();
        }
        int cy = y + CARD_H;
        if (expanded) {
            cy += 2;
            cy = drawSetting(ctx, pal, m.keybind(), m.name(), x + 8, cy, w - 16, mouseX, mouseY, clipTop, clipBottom);
            for (Setting<?> s : m.settings()) {
                if (!s.isVisible()) continue;
                cy = drawSetting(ctx, pal, s, m.name(), x + 8, cy, w - 16, mouseX, mouseY, clipTop, clipBottom);
            }
        }
        return cy;
    }

    private int drawSetting(DrawContext ctx, Palette pal, Setting<?> s, String owner, int x, int y, int w, int mouseX, int mouseY,
                            int clipTop, int clipBottom) {
        return drawSetting(ctx, pal, s, owner, s.name(), x, y, w, mouseX, mouseY, clipTop, clipBottom);
    }

    private int drawSetting(DrawContext ctx, Palette pal, Setting<?> s, String owner, String label, int x, int y, int w, int mouseX, int mouseY,
                            int clipTop, int clipBottom) {
        boolean visible = y + ROW_H > clipTop && y < clipBottom;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H && visible;
        if (hover) {
            ctx.fill(x, y, x + w, y + ROW_H, 0x18FFFFFF);
            tooltip = s.description();
        }
        UiKit.text(ctx, UiKit.ellipsize(label, w / 2 - 6), x + 4, y + 5, pal.text());
        int vx = x + w / 2, vw = w / 2 - 4;
        String id = owner + "." + s.name();
        if (s instanceof BooleanSetting b) {
            double on = anim.approach("sw." + id, b.get() ? 1 : 0);
            UiKit.roundRect(ctx, x + w - 30, y + 3, 24, 12, 6, UiKit.lerpColor(pal.outline(), pal.accent(), on));
            UiKit.roundRect(ctx, x + w - 28 + (int) Math.round(on * 12), y + 5, 8, 8, 4, 0xFFFFFFFF);
            if (visible) hits.add(x, y, w, ROW_H, null, b::toggle, b::reset);
        } else if (s instanceof NumberSetting n) {
            int tx = vx, tw = vw - 44;
            UiKit.roundRect(ctx, tx, y + 7, tw, 4, 2, pal.outline());
            double f = anim.approach("sl." + id, n.fraction());
            UiKit.roundRect(ctx, tx, y + 7, (int) (tw * f), 4, 2, pal.accent());
            UiKit.roundRect(ctx, tx + (int) (tw * f) - 4, y + 5, 8, 8, 4, 0xFFFFFFFF);
            UiKit.text(ctx, n.displayValue(), tx + tw + 6, y + 5, pal.textDim());
            if (visible) hits.add(tx - 4, y, tw + 8, ROW_H, null, () -> {
                draggingSlider = n;
                sliderX = tx;
                sliderW = tw;
            }, n::reset);
        } else if (s instanceof EnumSetting<?> e) {
            drawDropdownValue(ctx, pal, vx, y, vw, e.displayValue());
            if (visible) hits.add(vx, y, vw, ROW_H, null, () -> e.cycle(1), () -> e.cycle(-1));
        } else if (s instanceof ChoiceSetting c) {
            drawDropdownValue(ctx, pal, vx, y, vw, c.get().isEmpty() ? "(none)" : c.get());
            if (visible) hits.add(vx, y, vw, ROW_H, "Click to search", () -> openChoice(c), () -> openChoice(c));
        } else if (s instanceof ColorSetting c) {
            UiKit.roundRect(ctx, x + w - 40, y + 3, 34, 12, 3, 0xFF000000 | 0x303030);
            ctx.fill(x + w - 38, y + 5, x + w - 8, y + 13, c.argb());
            UiKit.text(ctx, c.displayValue(), x + w - 100, y + 5, pal.textDim());
            if (visible) hits.add(x, y, w, ROW_H, null, () -> openPicker(c, x + w - 150, y + ROW_H), c::reset);
        } else if (s instanceof KeybindSetting k) {
            boolean capturing = model.capturing() == k;
            String keyText = capturing ? "Press a key... (Esc cancel, Del unbind)" : "Key: " + k.displayValue();
            drawDropdownValue(ctx, pal, vx, y, vw, keyText);
            if (visible) hits.add(vx, y, vw, ROW_H, null, () -> model.beginKeyCapture(k), () -> k.set(Keybind.NONE));
        } else if (s instanceof ActionSetting a) {
            boolean h = hover;
            UiKit.roundRect(ctx, vx, y + 2, vw, ROW_H - 4, 4, h ? pal.accent() : pal.cardHover());
            String lbl = UiKit.ellipsize(a.name(), vw - 8);
            UiKit.text(ctx, lbl, vx + (vw - UiKit.font().getWidth(lbl)) / 2, y + 5, pal.text());
            if (visible) hits.add(vx, y, vw, ROW_H, null, a::run, null);
        } else if (s instanceof StringSetting st) {
            boolean ed = editing == st;
            String v = ed ? editBuffer + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : st.get();
            UiKit.roundRect(ctx, vx, y + 2, vw, ROW_H - 4, 4, ed ? pal.cardHover() : pal.card());
            UiKit.text(ctx, UiKit.ellipsize(v.isEmpty() && !ed ? "(empty)" : v, vw - 8), vx + 4, y + 5, pal.text());
            if (visible) hits.add(vx, y, vw, ROW_H, null, () -> {
                editing = st;
                editBuffer.setLength(0);
                editBuffer.append(st.get());
                model.focusSearch(false);
            }, st::reset);
        }
        return y + ROW_H;
    }

    private void drawDropdownValue(DrawContext ctx, Palette pal, int x, int y, int w, String value) {
        UiKit.roundRect(ctx, x, y + 2, w, ROW_H - 4, 4, pal.card());
        UiKit.text(ctx, UiKit.ellipsize(value, w - 18), x + 5, y + 5, pal.text());
        UiKit.text(ctx, "<>", x + w - 14, y + 5, pal.textDim());
    }

    // ------------------------------------------------------------------ color picker

    private void openPicker(ColorSetting c, int x, int y) {
        pickerFor = c;
        pickerX = Math.max(4, Math.min(x, width - 154));
        pickerY = Math.max(4, Math.min(y, height - 134));
        pickerHsv = UiKit.argbToHsv(c.argb());
        pickerAlpha = c.alpha();
    }

    private void drawColorPicker(DrawContext ctx, Palette pal, int mouseX, int mouseY) {
        int x = pickerX, y = pickerY;
        UiKit.roundRect(ctx, x, y, 150, 130, 6, 0xF8141418);
        int sqX = x + 6, sqY = y + 6, sq = 100;
        for (int i = 0; i < sq; i += 2) {
            float s = i / (float) sq;
            ctx.fillGradient(sqX + i, sqY, sqX + i + 2, sqY + sq, UiKit.hsvToArgb(pickerHsv[0], s, 1, 255), 0xFF000000);
        }
        int mx = sqX + (int) (pickerHsv[1] * sq), my = sqY + (int) ((1 - pickerHsv[2]) * sq);
        ctx.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFFFFFFFF);
        int hueX = x + 112;
        for (int i = 0; i < sq; i += 2) {
            ctx.fill(hueX, sqY + i, hueX + 10, sqY + i + 2, UiKit.hsvToArgb(i / (float) sq, 1, 1, 255));
        }
        ctx.fill(hueX - 1, sqY + (int) (pickerHsv[0] * sq), hueX + 11, sqY + (int) (pickerHsv[0] * sq) + 1, 0xFFFFFFFF);
        int alX = x + 128;
        int rgb = UiKit.hsvToArgb(pickerHsv[0], pickerHsv[1], pickerHsv[2], 255);
        ctx.fillGradient(alX, sqY, alX + 10, sqY + sq, rgb, rgb & 0x00FFFFFF);
        ctx.fill(alX - 1, sqY + (int) ((1 - pickerAlpha / 255f) * sq), alX + 11, sqY + (int) ((1 - pickerAlpha / 255f) * sq) + 1, 0xFFFFFFFF);
        int current = (pickerAlpha << 24) | (rgb & 0xFFFFFF);
        ctx.fill(x + 6, y + 112, x + 40, y + 124, current);
        UiKit.text(ctx, String.format("#%08X", current), x + 46, y + 114, pal.text());
        hits.add(sqX, sqY, sq, sq, null, () -> pickerDrag = 1, null);
        hits.add(hueX, sqY, 10, sq, null, () -> pickerDrag = 2, null);
        hits.add(alX, sqY, 10, sq, null, () -> pickerDrag = 3, null);
    }

    private void updatePicker(double mx, double my) {
        int sqX = pickerX + 6, sqY = pickerY + 6, sq = 100;
        double fy = Math.max(0, Math.min(1, (my - sqY) / sq));
        switch (pickerDrag) {
            case 1 -> {
                pickerHsv[1] = (float) Math.max(0, Math.min(1, (mx - sqX) / sq));
                pickerHsv[2] = (float) (1 - fy);
            }
            case 2 -> pickerHsv[0] = (float) fy;
            case 3 -> pickerAlpha = (int) Math.round((1 - fy) * 255);
            default -> { return; }
        }
        int rgb = UiKit.hsvToArgb(pickerHsv[0], pickerHsv[1], pickerHsv[2], 255);
        pickerFor.set((pickerAlpha << 24) | (rgb & 0xFFFFFF));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        if (choiceFor != null) {
            boolean inside = mx >= choiceX && my >= choiceY && mx < choiceX + choiceW && my < choiceY + choiceH;
            if (!inside) {
                choiceFor = null;
            } else if (my >= choiceListY && mx >= choiceX + 8 && mx < choiceX + choiceW - 8) {
                int row = (int) ((my - choiceListY) / CHOICE_ROW);
                if (row < choiceRows.size()) pickChoice(choiceRows.get(row));
            }
            return true;
        }
        if (pickerFor != null) {
            boolean inside = mx >= pickerX && my >= pickerY && mx < pickerX + 150 && my < pickerY + 130;
            if (!inside) {
                pickerFor = null;
                Atlas.get().config.markDirty();
                return true;
            }
        }
        if (editing != null) {
            editing.set(editBuffer.toString());
            editing = null;
        }
        model.focusSearch(false);
        UiKit.Hit hit = hits.at(mx, my);
        if (hit != null) {
            Runnable r = button == 1 ? hit.onRight() : hit.onLeft();
            if (r != null) {
                r.run();
                if (draggingSlider != null) {
                    GuiModel.sliderValue(draggingSlider, mx, sliderX, sliderW);
                }
                if (pickerDrag != 0) {
                    updatePicker(mx, my);
                }
                Atlas.get().config.markDirty();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingSlider != null) {
            GuiModel.sliderValue(draggingSlider, click.x(), sliderX, sliderW);
            return true;
        }
        if (pickerDrag != 0 && pickerFor != null) {
            updatePicker(click.x(), click.y());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingSlider != null || pickerDrag != 0) {
            draggingSlider = null;
            pickerDrag = 0;
            Atlas.get().config.markDirty();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (choiceFor != null) {
            choiceScroll = Math.max(0, choiceScroll - (int) Math.signum(verticalAmount) * 3);
            choiceIndex = choiceScroll;
            return true;
        }
        model.scrollBy(-verticalAmount * 24);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (choiceFor != null) {
            List<String> matches = choiceMatches();
            if (input.isEscape()) {
                choiceFor = null;
            } else if (input.isEnter()) {
                if (!matches.isEmpty()) pickChoice(matches.get(Math.max(0, Math.min(choiceIndex, matches.size() - 1))));
            } else if (key == Keybind.KEY_BACKSPACE) {
                if (choiceQuery.length() > 0) choiceQuery.setLength(choiceQuery.length() - 1);
                choiceIndex = 0;
            } else if (key == 265) { // up
                choiceIndex = Math.max(0, choiceIndex - 1);
            } else if (key == 264) { // down
                choiceIndex = Math.min(Math.max(0, matches.size() - 1), choiceIndex + 1);
            }
            return true;
        }
        if (editing != null) {
            if (key == Keybind.KEY_BACKSPACE && editBuffer.length() > 0) {
                editBuffer.setLength(editBuffer.length() - 1);
            } else if (input.isEnter()) {
                editing.set(editBuffer.toString());
                editing = null;
                Atlas.get().config.markDirty();
            } else if (input.isEscape()) {
                editing = null;
            }
            return true;
        }
        if (model.keyPressed(key)) {
            Atlas.get().config.markDirty();
            return true;
        }
        if (pickerFor != null && input.isEscape()) {
            pickerFor = null;
            return true;
        }
        GuiSettingsModule gs = Atlas.get().modules.get(GuiSettingsModule.class);
        if (gs != null && key == gs.openKey.key() && !model.searchFocused()) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (!input.isValidChar()) {
            return false;
        }
        String s = input.asString();
        if (choiceFor != null) {
            if (choiceQuery.length() < 64) choiceQuery.append(s);
            choiceIndex = 0;
            choiceScroll = 0;
            return true;
        }
        if (editing != null) {
            if (editBuffer.length() < 256) editBuffer.append(s);
            return true;
        }
        if (model.searchFocused()) {
            for (char c : s.toCharArray()) model.typeChar(c);
            return true;
        }
        // Typing anywhere starts a search (full view only).
        if (!simple && model.capturing() == null && !s.isBlank()) {
            model.focusSearch(true);
            for (char c : s.toCharArray()) model.typeChar(c);
            return true;
        }
        return super.charTyped(input);
    }
}
