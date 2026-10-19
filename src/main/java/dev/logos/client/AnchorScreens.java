package dev.logos.client;

import dev.logos.LogosMod;
import dev.logos.net.AnchorEditPayload;
import dev.logos.net.AnchorsPayload;
import dev.logos.runtime.Anchors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/** The anchor nail's two screens: naming a new anchor, and the list of your anchors. */
final class AnchorScreens {
    static final Identifier ICON = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/misc/anchor.png");
    private static final int GOLD = 0xFFE8B84A, TEXT = 0xFFE6E2D3, DIM = 0xFF9F9FAF;

    private AnchorScreens() {}

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xE0141018);
        g.outline(x, y, w, h, 0xFFB57FE0);
    }

    /** Name the anchor being driven in, on a block face or into the block itself. An existing name moves that anchor here. */
    static final class Name extends Screen {
        private static final int W = 220, MAX_SHOWN = 8;
        private final BlockPos pos;
        private final Direction face;
        /** Into the clicked block itself rather than on its face; sneaking when clicking chooses it. */
        private boolean into;
        private EditBox box;

        Name(BlockPos pos, Direction face, boolean into) {
            super(Component.literal("立下锚点"));
            this.pos = pos;
            this.face = face;
            this.into = into;
        }

        private Component placeLabel() {
            BlockPos at = into ? pos : pos.relative(face);
            return Component.literal((into ? "位置：钉进这个方块 " : "位置：立在这一面外 ") + at.toShortString());
        }

        @Override
        protected void init() {
            int x = (width - W) / 2, y = height / 2 - 70;
            box = addRenderableWidget(new EditBox(font, x + 10, y + 30, W - 20, 18, Component.literal("锚点名")));
            box.setMaxLength(Anchors.NAME_MAX);
            box.setHint(Component.literal("锚点名，如 家、矿洞、二楼"));
            setInitialFocus(box);
            var names = ClientAnchors.all();
            for (int i = 0; i < Math.min(MAX_SHOWN, names.size()); i++) {
                String n = names.get(i).name();
                int bx = x + 10 + (i % 4) * 50, by = y + 70 + (i / 4) * 22;
                addRenderableWidget(Button.builder(Component.literal(n), b -> box.setValue(n)).bounds(bx, by, 48, 20).build());
            }
            addRenderableWidget(Button.builder(placeLabel(), b -> {
                into = !into;
                b.setMessage(placeLabel());
            }).bounds(x + 10, y + 120, W - 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("立下"), b -> confirm()).bounds(x + 10, y + 146, (W - 30) / 2, 20).build());
            addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose()).bounds(x + 20 + (W - 30) / 2, y + 146, (W - 30) / 2, 20).build());
        }

        private void confirm() {
            String n = Anchors.clean(box.getValue());
            if (n == null) return;
            ClientPacketDistributor.sendToServer(AnchorEditPayload.set(n, pos, face, into));
            onClose();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                confirm();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            super.extractBackground(g, mouseX, mouseY, a);
            int x = (width - W) / 2, y = height / 2 - 70;
            panel(g, x, y, W, 174);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            super.extractRenderState(g, mouseX, mouseY, a);
            int x = (width - W) / 2, y = height / 2 - 70;
            g.blit(ICON, x + 8, y + 6, x + 24, y + 22, 0, 1, 0, 1);
            g.text(font, "立下锚点 · 消耗一枚锚钉", x + 28, y + 11, GOLD);
            boolean taken = ClientAnchors.all().stream().anyMatch(e -> e.name().equals(box.getValue().strip()));
            String hint = Anchors.clean(box.getValue()) == null ? "1~" + Anchors.NAME_MAX + " 个字，不能有空格或括号"
                    : taken ? "将把 ^" + box.getValue().strip() + " 移到这里" : "术式里写作 ^" + box.getValue().strip();
            g.text(font, hint, x + 10, y + 52, taken ? 0xFFE08A3F : DIM);
            if (!ClientAnchors.all().isEmpty()) g.text(font, "已有的锚点（点击以移动它）", x + 10, y + 62, DIM);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** Every anchor with its place, distance and a button to pull it up. */
    static final class List extends Screen {
        private static final int W = 300, ROW = 22, ROWS = 8;
        private int scroll;

        List() {
            super(Component.literal("锚点"));
        }

        void refresh() {
            rebuildWidgets();
        }

        @Override
        protected void init() {
            var all = ClientAnchors.all();
            scroll = Math.max(0, Math.min(scroll, all.size() - ROWS));
            int x = (width - W) / 2, y = top();
            for (int i = 0; i < Math.min(ROWS, all.size() - scroll); i++) {
                String n = all.get(scroll + i).name();
                addRenderableWidget(Button.builder(Component.literal("撤去"), b -> ClientPacketDistributor.sendToServer(AnchorEditPayload.remove(n)))
                        .bounds(x + W - 46, y + 28 + i * ROW, 40, 18).build());
            }
        }

        private int top() {
            return height / 2 - (ROWS * ROW + 40) / 2;
        }

        @Override
        public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
            int before = scroll;
            scroll = Math.max(0, Math.min(scroll + (scrollY < 0 ? 1 : -1), ClientAnchors.all().size() - ROWS));
            if (scroll != before) rebuildWidgets();
            return true;
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            super.extractBackground(g, mouseX, mouseY, a);
            panel(g, (width - W) / 2, top(), W, ROWS * ROW + 40);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            super.extractRenderState(g, mouseX, mouseY, a);
            var all = ClientAnchors.all();
            int x = (width - W) / 2, y = top();
            g.blit(ICON, x + 8, y + 6, x + 24, y + 22, 0, 1, 0, 1);
            g.text(font, "我的锚点 " + all.size() + " · 拿着锚钉右键方块立下新锚点", x + 28, y + 11, GOLD);
            if (all.isEmpty()) g.text(font, "还没有锚点", x + 12, y + 34, DIM);
            var player = Minecraft.getInstance().player;
            String here = player == null ? "" : player.level().dimension().identifier().toString();
            for (int i = 0; i < Math.min(ROWS, all.size() - scroll); i++) {
                AnchorsPayload.Entry e = all.get(scroll + i);
                int ry = y + 28 + i * ROW;
                g.blit(ICON, x + 8, ry + 1, x + 24, ry + 17, 0, 1, 0, 1);
                g.text(font, "^" + e.name(), x + 28, ry + 5, TEXT);
                String where = String.format("%d, %d, %d", (int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z()));
                String far = e.dimension().equals(here) && player != null
                        ? String.format(" · %d 格", (int) player.position().distanceTo(new Vec3(e.x(), e.y(), e.z())))
                        : " · " + e.dimension().replace("minecraft:", "");
                g.text(font, where + far, x + 110, ry + 5, DIM);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
