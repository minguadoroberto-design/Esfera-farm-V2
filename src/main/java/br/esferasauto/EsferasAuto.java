package br.esferasauto;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Esferas do Dragao (servidor Rede Warriors + DragonMineZ).
 * K (parado): abre o menu. K (rodando): para.
 * Voo: sobe ate as nuvens, cruza ate ficar em cima da esfera e so entao desce para encostar nela.
 */
public final class EsferasAuto {
    static final String EC = "com.nonopichy.yggdrasilremake.client.warriors.esferas.EsferasClient";
    static final String FLY = "com.dragonminez.client.events.FlySkillEvent";
    static final String PROV = "com.dragonminez.common.stats.StatsProvider";
    static final String CAP = "com.dragonminez.common.stats.StatsCapability";

    /** Altura (Y) de cruzeiro. Mais alto que isso o servidor joga voce no espaco. */
    static final int ALTURA_CRUZEIRO = 207;

    static final KeyMapping KEY = new KeyMapping("Esferas: abrir menu / parar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "Esferas AutoFarm");

    enum S { OFF, SYNC, FLYON, CLIMB, ESCAPE, CRUISE, DESCEND, TOUCH }
    static S s = S.OFF;

    // escolhas feitas no menu
    static byte planetaEscolhido = 0; // 0 = Terra, 1 = Namek
    static final Set<Integer> escolhidas = new HashSet<>(Set.of(1, 2, 3, 4, 5, 6, 7));

    static byte planeta;
    static int wait, t, ticksBola, quedas, cruiseY;
    static int alvoEstrelas;
    static double ultimaY;
    static final Set<Integer> ignoradas = new HashSet<>();

    private EsferasAuto() {}

    @Mod.EventBusSubscriber(modid = "esferasauto", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void teclas(RegisterKeyMappingsEvent e) { e.register(KEY); }
    }

    @Mod.EventBusSubscriber(modid = "esferasauto", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase == TickEvent.Phase.END) EsferasAuto.tick(Minecraft.getInstance());
        }
    }

    // ---------------- loop principal ----------------
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { if (s != S.OFF) { soltar(mc); s = S.OFF; } return; }
        while (KEY.consumeClick()) {
            if (s != S.OFF) parar(mc, "Parado.");
            else if (mc.screen == null) mc.setScreen(new MenuEsferas());
        }
        if (s == S.OFF) return;
        if (mc.screen != null) { parar(mc, "Um menu abriu, parei."); return; }
        try { passo(mc); }
        catch (Throwable ex) { ex.printStackTrace(); parar(mc, "Erro: " + ex); }
    }

    static void iniciar(Minecraft mc) {
        if (mc.screen != null) return;
        if (escolhidas.isEmpty()) { msg(mc, "Marque pelo menos uma esfera no menu (tecla K)."); return; }
        ignoradas.clear();
        quedas = 0;
        try { pedir(); } catch (Throwable ex) { msg(mc, "Nao achei o mod do servidor: " + ex); return; }
        s = S.SYNC; wait = 25; t = 0;
        msg(mc, "Ligado. Lendo posicoes das esferas...");
    }

    static void parar(Minecraft mc, String texto) {
        s = S.OFF;
        soltar(mc);
        if (texto != null) msg(mc, texto);
    }

    static void passo(Minecraft mc) throws Exception {
        LocalPlayer p = mc.player;
        switch (s) {
            case OFF -> {}
            case SYNC -> {
                if (wait-- > 0) return;
                int pl = achaPlaneta(mc);
                if (pl < 0) {
                    if (++t < 4) { pedir(); wait = 20; return; }
                    parar(mc, "Va ate o Mundo de Historia, na Terra ou em Namek (menu ESC), e aperte K de novo.");
                    return;
                }
                if (pl != planetaEscolhido) {
                    parar(mc, "Voce esta " + (pl == 0 ? "na Terra" : "em Namek") + ", mas escolheu " + (planetaEscolhido == 0 ? "a Terra" : "Namek") + " no menu.");
                    return;
                }
                planeta = (byte) pl;
                if (!bool("liberado", planeta)) { parar(mc, "Esferas bloqueadas: " + str("trava", planeta)); return; }
                escolher(mc);
            }
            case FLYON -> {
                if (wait-- > 0) return;
                if (voando(p)) { comecarSubida(mc, p); return; }
                if (++t > 3) { parar(mc, "Nao consegui ligar o voo. Ligue com F e aperte K de novo."); return; }
                alternarVoo();
                wait = 20;
            }
            default -> voo(mc, p);
        }
    }

    static void comecarSubida(Minecraft mc, LocalPlayer p) throws Exception {
        Object cur = achar(alvoEstrelas);
        int by = cur == null ? (int) p.getY() : inteiro(cur, "y");
        int topo = mc.level.getMaxBuildHeight() - 8;
        cruiseY = Math.min(topo, Math.max(ALTURA_CRUZEIRO, by + 15));
        ultimaY = p.getY();
        ticksBola = 0;
        s = S.CLIMB;
        msg(mc, "Subindo ate as nuvens (y=" + cruiseY + ")...");
    }

    /** Fases de voo: CLIMB (sobe), ESCAPE (desvia de teto), CRUISE (cruza em cima), DESCEND (desce), TOUCH (encosta). */
    static void voo(Minecraft mc, LocalPlayer p) throws Exception {
        ticksBola++;
        if (ticksBola > 20 * 240) { pular(mc, "demorou demais"); return; }
        if (!voando(p)) {
            soltar(mc);
            if (++quedas > 4) { parar(mc, "O voo desligou varias vezes (sem ki?)."); return; }
            s = S.FLYON; t = 0; wait = 0;
            return;
        }
        if (ticksBola % 40 == 0) pedir();
        Object cur = achar(alvoEstrelas);
        if (cur == null || coletada(cur)) { pegou(mc); return; }
        Vec3 b = centro(cur);
        double dx = b.x - p.getX(), dy = b.y - (p.getY() + 0.9), dz = b.z - p.getZ();
        double h = Math.sqrt(dx * dx + dz * dz), d = Math.sqrt(h * h + dy * dy);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        switch (s) {
            case CLIMB -> {
                p.setYRot(yaw);
                p.setXRot(-90f);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(p.getY() < cruiseY - 25); // perto do topo, sem correr: nao passa da altura
                if (p.getY() >= cruiseY - 6) { s = S.CRUISE; msg(mc, "Nas nuvens. Indo ate a esfera de " + alvoEstrelas + " estrela(s)."); return; }
                if (ticksBola % 30 == 0) {
                    if (p.getY() - ultimaY < 1.0) { s = S.ESCAPE; t = 0; }
                    ultimaY = p.getY();
                }
            }
            case ESCAPE -> {
                p.setYRot(yaw);
                p.setXRot(0f);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                if (++t > 30) { s = S.CLIMB; ultimaY = p.getY(); }
            }
            case CRUISE -> {
                if (h < 3.5) { s = S.DESCEND; msg(mc, "Em cima da esfera. Descendo."); return; }
                p.setYRot(yaw);
                float pitch = (float) Math.max(-35, Math.min(35, -(cruiseY - p.getY()) * 4));
                p.setXRot(pitch);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(h > 30);
            }
            case DESCEND -> {
                if (h > 12 && p.getY() > b.y + 25) { s = S.CRUISE; return; } // passou do ponto: recentra la em cima
                if (d < 1.6) { soltar(mc); s = S.TOUCH; t = 0; return; }
                mirar(p, dx, dy, dz);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(d > 14);
            }
            case TOUCH -> {
                t++;
                mirar(p, dx, dy, dz);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(false);
                if (t > 100) { soltar(mc); pular(mc, "nao consegui encostar"); }
            }
            default -> {}
        }
    }

    static void pegou(Minecraft mc) throws Exception {
        soltar(mc);
        msg(mc, "Esfera de " + alvoEstrelas + " estrela(s) pega!");
        escolher(mc);
    }

    static void pular(Minecraft mc, String motivo) throws Exception {
        ignoradas.add(alvoEstrelas);
        msg(mc, "Pulando a esfera de " + alvoEstrelas + " estrela(s): " + motivo + ".");
        escolher(mc);
    }

    /** Escolhe a esfera marcada no menu, ainda nao coletada, mais proxima. */
    static void escolher(Minecraft mc) throws Exception {
        Vec3 pos = mc.player.position();
        Object melhor = null; double md = Double.MAX_VALUE;
        for (Object e : esferas(planeta)) {
            int est = inteiro(e, "estrelas");
            if (!escolhidas.contains(est) || coletada(e) || ignoradas.contains(est)) continue;
            double d = centro(e).distanceTo(pos);
            if (d < md) { md = d; melhor = e; }
        }
        if (melhor == null) { parar(mc, "Pronto: nao ha mais esferas marcadas para pegar neste planeta."); return; }
        alvoEstrelas = inteiro(melhor, "estrelas");
        msg(mc, "Proxima: esfera de " + alvoEstrelas + " estrela(s) (" + (int) md + " blocos).");
        s = S.FLYON; t = 0; wait = 0;
    }

    // ---------------- util ----------------
    static void mirar(LocalPlayer p, double dx, double dy, double dz) {
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    static void soltar(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    static void msg(Minecraft mc, String t) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Esferas] " + t), false);
    }

    static Vec3 centro(Object e) throws Exception {
        return new Vec3(inteiro(e, "x") + 0.5, inteiro(e, "y") + 0.5, inteiro(e, "z") + 0.5);
    }

    static Object achar(int estrelas) throws Exception { return acharEm(planeta, estrelas); }

    static Object acharEm(byte pl, int estrelas) throws Exception {
        for (Object e : esferas(pl)) if (inteiro(e, "estrelas") == estrelas) return e;
        return null;
    }

    static int achaPlaneta(Minecraft mc) throws Exception {
        String dim = mc.level.dimension().location().toString();
        for (byte b = 0; b <= 1; b++)
            if (bool("conhecido", b) && dim.equals(str("dimensao", b))) return b;
        return -1;
    }

    // ---------------- reflexao: mod do servidor ----------------
    static Class<?> classe(String nome) throws Exception {
        return Class.forName(nome, true, Minecraft.class.getClassLoader());
    }

    static void pedir() throws Exception { classe(EC).getMethod("pedir").invoke(null); }

    static List<?> esferas(byte pl) throws Exception { return (List<?>) classe(EC).getMethod("esferas", byte.class).invoke(null, pl); }

    static boolean bool(String m, byte pl) throws Exception { return (Boolean) classe(EC).getMethod(m, byte.class).invoke(null, pl); }

    static String str(String m, byte pl) throws Exception { return String.valueOf(classe(EC).getMethod(m, byte.class).invoke(null, pl)); }

    static int inteiro(Object rec, String m) throws Exception { return ((Number) rec.getClass().getMethod(m).invoke(rec)).intValue(); }

    static boolean coletada(Object rec) throws Exception { return (Boolean) rec.getClass().getMethod("coletada").invoke(rec); }

    // ---------------- reflexao: voo do DragonMineZ ----------------
    static void alternarVoo() throws Exception { classe(FLY).getMethod("toggleFlightFromMenu").invoke(null); }

    static boolean voando(LocalPlayer p) {
        try {
            Object cap = classe(CAP).getField("INSTANCE").get(null);
            Method get = classe(PROV).getMethod("get", Capability.class, Entity.class);
            LazyOptional<?> lo = (LazyOptional<?>) get.invoke(null, cap, p);
            Optional<?> stats = lo.resolve();
            if (stats.isEmpty()) return false;
            Object st = stats.get();
            Object skills = st.getClass().getMethod("getSkills").invoke(st);
            Object skill = skills.getClass().getMethod("getSkill", String.class).invoke(skills, "fly");
            return skill != null && (Boolean) skill.getClass().getMethod("isActive").invoke(skill);
        } catch (Throwable ex) {
            return Minecraft.getInstance().level.getBlockState(p.blockPosition().below()).isAir(); // plano B: se ha ar embaixo, considera voando
        }
    }
}
