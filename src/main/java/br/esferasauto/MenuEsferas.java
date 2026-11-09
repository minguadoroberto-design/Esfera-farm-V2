package br.esferasauto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Menu para escolher o planeta e quais esferas pegar. */
public class MenuEsferas extends Screen {
    private final List<Button> bolas = new ArrayList<>();
    private Button btnTerra, btnNamek;
    private String info = "";
    private int top;
    private int contador;

    public MenuEsferas() { super(Component.literal("Esferas do Dragao")); }

    @Override
    protected void init() {
        try { EsferasAuto.pedir(); } catch (Throwable ignored) {}
        int cx = width / 2;
        top = Math.max(8, (height - 214) / 2);
        int y0 = top + 28;
        bolas.clear();

        btnTerra = addRenderableWidget(Button.builder(Component.empty(), b -> { EsferasAuto.planetaEscolhido = 0; atualizar(); })
                .bounds(cx - 155, y0, 150, 20).build());
        btnNamek = addRenderableWidget(Button.builder(Component.empty(), b -> { EsferasAuto.planetaEscolhido = 1; atualizar(); })
                .bounds(cx + 5, y0, 150, 20).build());

        for (int i = 0; i < 7; i++) {
            final int est = i + 1;
            int col = i / 4, row = i % 4;
            Button b = Button.builder(Component.empty(), bt -> {
                if (!EsferasAuto.escolhidas.remove(est)) EsferasAuto.escolhidas.add(est);
                atualizar();
            }).bounds(cx - 155 + col * 160, y0 + 32 + row * 24, 150, 20).build();
            bolas.add(addRenderableWidget(b));
        }

        int yb = y0 + 32 + 4 * 24 + 6;
        addRenderableWidget(Button.builder(Component.literal("Marcar todas"), b -> {
            for (int i = 1; i <= 7; i++) EsferasAuto.escolhidas.add(i);
            atualizar();
        }).bounds(cx - 155, yb, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Desmarcar todas"), b -> {
            EsferasAuto.escolhidas.clear();
            atualizar();
        }).bounds(cx + 5, yb, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("INICIAR"), b -> {
            onClose();
            EsferasAuto.iniciar(Minecraft.getInstance());
        }).bounds(cx - 155, yb + 26, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fechar"), b -> onClose())
                .bounds(cx + 5, yb + 26, 150, 20).build());
        atualizar();
    }

    private void atualizar() {
        btnTerra.setMessage(Component.literal((EsferasAuto.planetaEscolhido == 0 ? "[X] " : "[  ] ") + "Terra"));
        btnNamek.setMessage(Component.literal((EsferasAuto.planetaEscolhido == 1 ? "[X] " : "[  ] ") + "Namek"));
        byte pl = EsferasAuto.planetaEscolhido;
        boolean conhecido = false;
        try { conhecido = EsferasAuto.bool("conhecido", pl); } catch (Throwable ignored) {}
        for (int i = 0; i < 7; i++) {
            int est = i + 1;
            String status = "";
            if (conhecido) {
                try {
                    Object e = EsferasAuto.acharEm(pl, est);
                    status = e == null ? "" : (EsferasAuto.coletada(e) ? "  (pega)" : "  (no mapa)");
                } catch (Throwable ignored) {}
            }
            bolas.get(i).setMessage(Component.literal((EsferasAuto.escolhidas.contains(est) ? "[X] " : "[  ] ") + est + " estrela(s)" + status));
        }
        String onde = "?";
        try {
            int a = EsferasAuto.achaPlaneta(Minecraft.getInstance());
            onde = a == 0 ? "Terra" : a == 1 ? "Namek" : "fora do Mundo de Historia";
        } catch (Throwable ignored) {}
        info = "Voce esta: " + onde + ". Escolha o planeta e as esferas e clique em INICIAR.";
    }

    @Override
    public void tick() {
        if (++contador % 20 == 0) atualizar();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, "Esferas do Dragao", width / 2, top, 0xFFAA00);
        g.drawCenteredString(font, info, width / 2, top + 12, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
