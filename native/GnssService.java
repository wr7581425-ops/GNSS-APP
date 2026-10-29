package br.com.coletoragnss.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Serviço em primeiro plano: mantém o WA Survey funcionando com a tela apagada, com outro app aberto
 * ou depois de tirar o app dos recentes (receptor, NTRIP, gravação estática e servidor NMEA continuam).
 */
public class GnssService extends Service {
    static final String CANAL = "wasurvey_campo";
    static final int ID = 7101;
    static final String ACAO_DESLIGAR = "br.com.coletoragnss.app.DESLIGAR";
    static volatile boolean rodando = false;
    static volatile String texto = "WA Survey ligado";

    static void ligar(Context c, String t) {
        if (c == null) return;
        texto = t;
        if (rodando) { atualizar(c, t); return; }
        Intent i = new Intent(c, GnssService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void desligar(Context c) {
        if (c == null || !rodando) return;
        c.stopService(new Intent(c, GnssService.class));
    }

    static void atualizar(Context c, String t) {
        if (c == null) return;
        texto = t;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, montar(c, t));
    }

    private static Notification montar(Context c, String t) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CANAL) == null) {
                NotificationChannel ch = new NotificationChannel(CANAL, "Trabalho em campo", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Mantém o receptor, a correção e a gravação ligados com o app fechado");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        Intent abrir = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (abrir == null) abrir = new Intent(c, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pAbrir = PendingIntent.getActivity(c, 1, abrir, flags);
        Intent desl = new Intent(c, GnssService.class).setAction(ACAO_DESLIGAR);
        PendingIntent pDesl = PendingIntent.getService(c, 2, desl, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
        int icone = c.getApplicationInfo().icon;
        b.setSmallIcon(icone != 0 ? icone : android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("WA Survey em campo")
                .setContentText(t)
                .setStyle(new Notification.BigTextStyle().bigText(t))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pAbrir)
                .addAction(new Notification.Action.Builder(0, "Desligar tudo", pDesl).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_DESLIGAR.equals(intent.getAction())) {
            GnssTcpPlugin.desligarTudo();
            rodando = false;
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = montar(this, texto);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(ID, n);
            rodando = true;
        } catch (Exception e) {
            // sem permissão para serviço em primeiro plano: o app segue funcionando com a tela aberta
            rodando = false;
            stopSelf();
        }
        // se o Android matar o processo, não adianta reabrir o serviço sozinho (as conexões morreram junto)
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app tirado dos recentes: o serviço continua (as conexões seguem ligadas)
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        rodando = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
