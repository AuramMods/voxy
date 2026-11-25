package me.cortex.voxy.client.config;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Predicate;

record ServerOptionListControl(Option<String> option, Predicate<String> validator) implements Control<String> {
    @Override
    public Option<String> getOption() {
        return this.option;
    }

    @Override
    public int getMaxWidth() {
        return 40;
    }

    @Override
    public ControlElement<String> createElement(Dim2i dim) {
        return new ControlElement<>(this.option, dim) {
            @Override
            public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                super.render(graphics, mouseX, mouseY, delta);
                var edit = Component.translatable("voxy.config.server.edit").getString();
                this.drawString(graphics, edit, this.dim.getLimitX() - this.font.width(edit) - 6,
                        this.dim.getCenterY() - 4, 0xFFFFFFFF);
            }

            @Override
            public boolean mouseClicked(double x, double y, int button) {
                return button == 0 && this.dim.containsCursor(x, y) && openEditor();
            }

            @Override
            public boolean keyPressed(int key, int scanCode, int modifiers) {
                return this.isFocused() && CommonInputs.selected(key) && openEditor();
            }
        };
    }

    private boolean openEditor() {
        var client = Minecraft.getInstance();
        client.setScreen(new Editor(client.screen));
        return true;
    }

    static List<String> entries(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private final class Editor extends Screen {
        private final Screen parent;
        private String text = option.getValue();
        private Button done;
        private String invalid;

        private Editor(Screen parent) {
            super(option.getName());
            this.parent = parent;
        }

        @Override
        protected void init() {
            int fieldWidth = Math.min(400, this.width - 24);
            int x = (this.width - fieldWidth) / 2;
            var field = new MultiLineEditBox(this.font, x, 54, fieldWidth, Math.max(32, this.height - 116),
                    Component.empty(), this.title);
            this.done = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
                option.setValue(String.join("\n", entries(this.text)));
                this.onClose();
            }).bounds(this.width / 2 - 104, this.height - 28, 100, 20).build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                    .bounds(this.width / 2 + 4, this.height - 28, 100, 20).build());
            field.setValue(this.text);
            field.setValueListener(this::validate);
            this.validate(this.text);
            this.addRenderableWidget(field);
            this.setInitialFocus(field);
        }

        private void validate(String value) {
            this.text = value;
            this.invalid = entries(value).stream().filter(validator.negate()).findFirst().orElse(null);
            this.done.active = this.invalid == null;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            this.renderBackground(graphics);
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
            int fieldWidth = Math.min(400, this.width - 24);
            int x = (this.width - fieldWidth) / 2;
            graphics.drawWordWrap(this.font, option.getTooltip(), x, 26, fieldWidth, 0xCCCCCC);
            super.render(graphics, mouseX, mouseY, delta);
            if (this.invalid != null) {
                var value = this.font.plainSubstrByWidth(this.invalid, fieldWidth / 2);
                graphics.drawCenteredString(this.font, Component.translatable("voxy.config.server.invalid", value),
                        this.width / 2, this.height - 47, 0xFF5555);
            }
        }

        @Override
        public void onClose() {
            this.minecraft.setScreen(this.parent);
        }
    }
}
