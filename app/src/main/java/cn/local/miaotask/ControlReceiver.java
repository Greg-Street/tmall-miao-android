package cn.local.miaotask;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("cn.local.miaotask.STOP".equals(intent.getAction()) && MiaoService.instance != null) MiaoService.instance.stopRun();
    }
}
