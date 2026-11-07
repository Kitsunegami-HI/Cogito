package dev.cogito.ui;

import dev.cogito.llm.Llm;
import dev.cogito.llm.LlmSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the person sets up model providers: which provider and model the planner and the executor use, and each
 * provider's address, key and prices. Keys typed here show as stars, are never shown again (only their last four
 * characters), and go to {@link LlmSettings}, outside the game folder. Nothing is saved until Save.
 */
public final class LlmSettingsScreen extends Screen {
    private static final int WIDTH = 368;
    private static final int LABEL = 100;
    private static final int FIELD = WIDTH - LABEL - 4;
    private static final int GAP = 4;

    private static volatile String pending;

    private final @Nullable Screen parent;
    private final @Nullable String notice;
    private final List<Draft> drafts = new ArrayList<>();
    private final RoleDraft planner;
    private final RoleDraft executor;
    private int editing;
    private Component status = Component.empty();

    private HeaderAndFooterLayout layout;
    private ScrollableLayout scroller;
    private MultiLineTextWidget statusText;
    private MultiLineTextWidget keyState;
    private StringWidget kindLabel;
    private EditBox name;
    private EditBox baseUrl;
    private EditBox key;
    private EditBox keyVariable;
    private EditBox inPrice;
    private EditBox outPrice;
    private CycleButton<Boolean> images;
    private EditBox plannerModel;
    private EditBox executorModel;

    public LlmSettingsScreen(@Nullable Screen parent) {
        this(parent, null);
    }

    public LlmSettingsScreen(@Nullable Screen parent, @Nullable String notice) {
        super(Component.translatable("cogito.llm.title"));
        this.parent = parent;
        this.notice = notice;
        LlmSettings.Settings settings = LlmSettings.get();
        for (LlmSettings.Profile profile : settings.profiles()) {
            drafts.add(new Draft(profile));
        }
        planner = new RoleDraft(settings.planner());
        executor = new RoleDraft(settings.executor());
        planner.profile = existing(planner.profile);
        executor.profile = existing(executor.profile);
        Draft used = find(planner.profile);
        editing = used == null ? 0 : drafts.indexOf(used);
    }

    /** Opens the screen on the next client tick, with a note on what is missing; for code that runs while another screen closes. */
    public static void openSoon(String notice) {
        pending = notice;
    }

    /** Client thread, every tick. */
    public static void tick(Minecraft minecraft) {
        String notice = pending;
        if (notice != null) {
            pending = null;
            minecraft.setScreen(new LlmSettingsScreen(minecraft.screen, notice));
        }
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 28, 52);
        layout.addTitleHeader(title, font);

        LinearLayout body = LinearLayout.vertical().spacing(GAP);
        body.defaultCellSetting().alignHorizontallyLeft();
        if (notice != null) {
            body.addChild(new MultiLineTextWidget(Component.literal(notice).withStyle(ChatFormatting.YELLOW), font).setMaxWidth(WIDTH));
        }
        body.addChild(heading("cogito.llm.roles"));
        plannerModel = roleRow(body, "cogito.llm.planner", planner);
        executorModel = roleRow(body, "cogito.llm.executor", executor);

        body.addChild(heading("cogito.llm.providers"));
        LinearLayout pick = LinearLayout.horizontal().spacing(GAP);
        pick.addChild(CycleButton.builder((Draft d) -> Component.literal(d.name.equals(d.kind.label) ? d.name : d.name + " · " + d.kind.label),
                        drafts.get(editing))
                .withValues(drafts)
                .create(0, 0, WIDTH - 64 - GAP, 20, Component.translatable("cogito.llm.editing"), (button, draft) -> switchTo(draft)));
        pick.addChild(Button.builder(Component.translatable("cogito.llm.delete"), b -> delete()).width(64).build());
        body.addChild(pick);
        LinearLayout add = LinearLayout.horizontal().spacing(GAP);
        int addWidth = (WIDTH - 2 * GAP) / 3;
        for (LlmSettings.Kind kind : LlmSettings.Kind.values()) {
            add.addChild(Button.builder(Component.translatable("cogito.llm.add", kind.label), b -> add(kind)).width(addWidth).build());
        }
        body.addChild(add);

        kindLabel = body.addChild(new StringWidget(WIDTH, 12, Component.empty(), font));
        name = field(body, "cogito.llm.name", 64, null);
        baseUrl = field(body, "cogito.llm.base_url", 256, "cogito.llm.base_url.tip");
        key = field(body, "cogito.llm.key", 512, "cogito.llm.key.tip");
        // What is typed shows as stars; the key itself is never put back into the box.
        key.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        key.setResponder(s -> refreshKeyState());
        LinearLayout keyRow = LinearLayout.horizontal().spacing(GAP);
        keyState = keyRow.addChild(new MultiLineTextWidget(Component.empty(), font).setMaxWidth(WIDTH - 90 - GAP));
        keyRow.addChild(Button.builder(Component.translatable("cogito.llm.clear_key"), b -> clearKey()).width(90).build());
        body.addChild(keyRow);
        keyVariable = field(body, "cogito.llm.key_variable", 128, "cogito.llm.key_variable.tip");
        keyVariable.setFilter(s -> s.matches("[A-Za-z0-9_\\-]*"));
        keyVariable.setResponder(s -> refreshKeyState());
        images = body.addChild(CycleButton.onOffBuilder(true).create(0, 0, WIDTH, 20, Component.translatable("cogito.llm.images")));
        images.setTooltip(Tooltip.create(Component.translatable("cogito.llm.images.tip")));

        LinearLayout prices = LinearLayout.horizontal().spacing(GAP);
        prices.addChild(label("cogito.llm.price", LABEL));
        int priceWidth = (FIELD - GAP) / 2;
        inPrice = prices.addChild(price("cogito.llm.price.in", priceWidth));
        outPrice = prices.addChild(price("cogito.llm.price.out", priceWidth));
        body.addChild(prices);

        body.addChild(Button.builder(Component.translatable("cogito.llm.test"), b -> test()).width(WIDTH).build());
        body.addChild(new MultiLineTextWidget(Component.translatable("cogito.llm.where", LlmSettings.DIR.toString())
                .withStyle(ChatFormatting.GRAY), font).setMaxWidth(WIDTH));

        scroller = new ScrollableLayout(minecraft, body, layout.getContentHeight());
        layout.addToContents(scroller);

        LinearLayout footer = LinearLayout.vertical().spacing(GAP);
        statusText = footer.addChild(new MultiLineTextWidget(status, font).setMaxWidth(WIDTH).setMaxRows(2));
        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        buttons.addChild(Button.builder(Component.translatable("cogito.llm.save"), b -> save()).width(150).build());
        buttons.addChild(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).width(150).build());
        footer.addChild(buttons);
        layout.addToFooter(footer);

        layout.visitWidgets(this::addRenderableWidget);
        loadDraft();
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (layout == null) {
            return;
        }
        scroller.arrangeElements();
        scroller.setMaxHeight(layout.getContentHeight());
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ---- building ----

    /** A label that keeps its width whatever its text, so the boxes after it line up; long text is cut short. */
    private StringWidget label(String key, int width) {
        return new StringWidget(width, 20, Component.translatable(key), font);
    }

    private StringWidget heading(String key) {
        return new StringWidget(WIDTH, 14, Component.translatable(key).withStyle(ChatFormatting.BOLD), font);
    }

    /** One layer: its provider, its model, and how hard it thinks. Returns the model box. */
    private EditBox roleRow(LinearLayout body, String label, RoleDraft role) {
        LinearLayout row = LinearLayout.horizontal().spacing(GAP);
        row.addChild(label(label, 60));
        List<String> ids = drafts.stream().map(d -> d.id).toList();
        CycleButton<String> provider = CycleButton.builder((String id) -> Component.literal(nameOf(id)), role.profile)
                .withValues(ids)
                .displayOnlyValue()
                .create(0, 0, 104, 20, Component.translatable("cogito.llm.provider"), (button, id) -> role.profile = id);
        provider.setTooltip(Tooltip.create(Component.translatable(label + ".tip")));
        row.addChild(provider);
        EditBox model = new EditBox(font, 112, 20, Component.translatable("cogito.llm.model"));
        model.setMaxLength(128);
        model.setValue(role.model);
        model.setHint(Component.translatable("cogito.llm.model.hint").withStyle(ChatFormatting.DARK_GRAY));
        model.setTooltip(Tooltip.create(Component.translatable("cogito.llm.model.tip")));
        row.addChild(model);
        CycleButton<String> effort = CycleButton.builder((String e) -> Component.translatable("cogito.llm.effort." + e), role.effort)
                .withValues(LlmSettings.EFFORTS)
                .displayOnlyValue()
                .create(0, 0, 80, 20, Component.translatable("cogito.llm.effort"), (button, e) -> role.effort = e);
        effort.setTooltip(Tooltip.create(Component.translatable("cogito.llm.effort.tip")));
        row.addChild(effort);
        body.addChild(row);
        return model;
    }

    private EditBox field(LinearLayout body, String label, int maxLength, @Nullable String tip) {
        LinearLayout row = LinearLayout.horizontal().spacing(GAP);
        row.addChild(label(label, LABEL));
        EditBox box = new EditBox(font, FIELD, 20, Component.translatable(label));
        box.setMaxLength(maxLength);
        if (tip != null) {
            box.setTooltip(Tooltip.create(Component.translatable(tip)));
        }
        row.addChild(box);
        body.addChild(row);
        return box;
    }

    private EditBox price(String hint, int width) {
        EditBox box = new EditBox(font, width, 20, Component.translatable(hint));
        box.setMaxLength(12);
        box.setFilter(s -> s.matches("[0-9]*\\.?[0-9]*"));
        box.setHint(Component.translatable(hint).withStyle(ChatFormatting.DARK_GRAY));
        box.setTooltip(Tooltip.create(Component.translatable("cogito.llm.price.tip")));
        return box;
    }

    // ---- editing ----

    /** The boxes' text back into the drafts. */
    private void saveDraft() {
        Draft d = drafts.get(editing);
        d.name = name.getValue().strip();
        d.baseUrl = baseUrl.getValue().strip();
        d.typedKey = key.getValue().strip();
        d.keyVariable = keyVariable.getValue().strip();
        d.images = images.getValue();
        d.inPrice = inPrice.getValue().strip();
        d.outPrice = outPrice.getValue().strip();
        planner.model = plannerModel.getValue().strip();
        executor.model = executorModel.getValue().strip();
    }

    /** The draft being edited into the boxes. */
    private void loadDraft() {
        Draft d = drafts.get(editing);
        kindLabel.setMessage(Component.translatable("cogito.llm.kind", d.kind.label).withStyle(ChatFormatting.GRAY));
        name.setValue(d.name);
        baseUrl.setValue(d.baseUrl);
        baseUrl.setHint(Component.literal(d.kind.officialUrl).withStyle(ChatFormatting.DARK_GRAY));
        key.setValue(d.typedKey);
        keyVariable.setValue(d.keyVariable);
        keyVariable.setHint(Component.literal(d.kind.keyVariables.getFirst()).withStyle(ChatFormatting.DARK_GRAY));
        images.setValue(d.images);
        inPrice.setValue(d.inPrice);
        outPrice.setValue(d.outPrice);
        refreshKeyState();
    }

    private void refreshKeyState() {
        if (keyState == null || key == null || keyVariable == null) {
            return;
        }
        Draft d = drafts.get(editing);
        Component state;
        if (!key.getValue().isBlank()) {
            state = Component.translatable("cogito.llm.key.typed");
        } else {
            LlmSettings.Profile profile = d.profile(baseUrl.getValue(), keyVariable.getValue()).withKey(d.clearKey ? "" : d.storedKey);
            LlmSettings.Key found = LlmSettings.key(profile);
            state = switch (found.source()) {
                case STORED -> Component.translatable("cogito.llm.key.stored", found.hint());
                case ENVIRONMENT -> Component.translatable("cogito.llm.key.env", found.variable(), found.hint());
                case NONE -> d.clearKey && !d.storedKey.isEmpty() ? Component.translatable("cogito.llm.key.cleared")
                        : Component.translatable(profile.keyOptional() ? "cogito.llm.key.optional" : "cogito.llm.key.none");
            };
        }
        keyState.setMessage(state.copy().withStyle(ChatFormatting.GRAY));
        repositionElements();
    }

    private void switchTo(Draft draft) {
        saveDraft();
        editing = drafts.indexOf(draft);
        loadDraft();
    }

    private void add(LlmSettings.Kind kind) {
        saveDraft();
        int n = 1;
        while (find(kind.id + (n == 1 ? "" : "-" + n)) != null) {
            n++;
        }
        String id = kind.id + (n == 1 ? "" : "-" + n);
        drafts.add(new Draft(new LlmSettings.Profile(id, kind.label + (n == 1 ? "" : " " + n), kind, "", "", "", true, -1, -1)));
        editing = drafts.size() - 1;
        rebuildWidgets();
    }

    private void delete() {
        if (drafts.size() == 1) {
            setStatus(Component.translatable("cogito.llm.last"));
            return;
        }
        saveDraft();
        Draft gone = drafts.remove(editing);
        String first = drafts.getFirst().id;
        if (planner.profile.equals(gone.id)) {
            planner.profile = first;
        }
        if (executor.profile.equals(gone.id)) {
            executor.profile = first;
        }
        editing = 0;
        rebuildWidgets();
    }

    private void clearKey() {
        Draft d = drafts.get(editing);
        d.clearKey = true;
        d.typedKey = "";
        key.setValue("");
        refreshKeyState();
    }

    private void save() {
        saveDraft();
        List<LlmSettings.Profile> profiles = new ArrayList<>();
        for (Draft d : drafts) {
            profiles.add(d.profile(d.baseUrl, d.keyVariable));
        }
        try {
            LlmSettings.save(new LlmSettings.Settings(profiles,
                    new LlmSettings.Role(planner.profile, planner.model, planner.effort),
                    new LlmSettings.Role(executor.profile, executor.model, executor.effort)));
        } catch (IOException e) {
            setStatus(Component.translatable("cogito.llm.save_failed", e.getClass().getSimpleName()).withStyle(ChatFormatting.RED));
            return;
        }
        onClose();
    }

    /** Lists the provider's models and asks the model a layer uses with it for one word, off the render thread. */
    private void test() {
        saveDraft();
        Draft d = drafts.get(editing);
        LlmSettings.Profile profile = d.profile(d.baseUrl, d.keyVariable);
        String model = planner.profile.equals(d.id) ? planner.model : executor.profile.equals(d.id) ? executor.model : "";
        setStatus(Component.translatable("cogito.llm.testing"));
        Thread thread = new Thread(() -> {
            Component result;
            try {
                result = describe(Llm.test(profile, model), model);
            } catch (RuntimeException e) {
                result = Component.literal(Llm.message(e)).withStyle(ChatFormatting.RED);
            }
            Component shown = result;
            minecraft.execute(() -> {
                if (minecraft.screen == this) {
                    setStatus(shown);
                }
            });
        }, "cogito-llm-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static Component describe(Llm.Test test, String model) {
        Component models = test.modelsError() != null
                ? Component.translatable("cogito.llm.test.models_failed", test.modelsError()).withStyle(ChatFormatting.RED)
                : Component.translatable("cogito.llm.test.models", test.models().size(),
                        String.join(", ", test.models().subList(0, Math.min(6, test.models().size()))));
        Component reply = model.isBlank() ? Component.translatable("cogito.llm.test.unused")
                : test.replyError() != null
                ? Component.translatable("cogito.llm.test.reply_failed", model, test.replyError()).withStyle(ChatFormatting.RED)
                : Component.translatable("cogito.llm.test.reply", model, test.reply()).withStyle(ChatFormatting.GREEN);
        return Component.empty().append(reply).append(" ").append(models);
    }

    private void setStatus(Component text) {
        status = text;
        if (statusText != null) {
            statusText.setMessage(text);
            statusText.setTooltip(Tooltip.create(text));
            repositionElements();
        }
    }

    private String nameOf(String id) {
        Draft d = find(id);
        return d == null ? id : d.name;
    }

    private @Nullable Draft find(String id) {
        for (Draft d : drafts) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    private String existing(String id) {
        return find(id) != null ? id : drafts.getFirst().id;
    }

    /** A provider as edited: the stored key is kept unless a new one is typed or it is cleared. */
    private static final class Draft {
        final String id;
        final LlmSettings.Kind kind;
        final String storedKey;
        String name;
        String baseUrl;
        String typedKey = "";
        boolean clearKey;
        String keyVariable;
        boolean images;
        String inPrice;
        String outPrice;

        Draft(LlmSettings.Profile p) {
            id = p.id();
            kind = p.kind();
            storedKey = p.apiKey();
            name = p.name();
            baseUrl = p.baseUrl();
            keyVariable = p.keyVariable();
            images = p.images();
            inPrice = p.inputPrice() < 0 ? "" : trim(p.inputPrice());
            outPrice = p.outputPrice() < 0 ? "" : trim(p.outputPrice());
        }

        LlmSettings.Profile profile(String url, String variable) {
            String key = !typedKey.isBlank() ? typedKey : clearKey ? "" : storedKey;
            return new LlmSettings.Profile(id, name, kind, url, key, variable, images, parse(inPrice), parse(outPrice));
        }

        private static double parse(String text) {
            try {
                return text.isBlank() ? -1 : Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static String trim(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    private static final class RoleDraft {
        String profile;
        String model;
        String effort;

        RoleDraft(LlmSettings.Role role) {
            profile = role.profile();
            model = role.model();
            effort = role.effort();
        }
    }
}
