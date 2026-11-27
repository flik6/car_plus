package com.chery.carplus.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

abstract class BaseCarWidgetProvider(private val style: CarWidgetStyle) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        CarWidgetManager.updateWidgetsForStyle(context, appWidgetManager, appWidgetIds, style)
        CarWidgetManager.triggerBackgroundSyncIfStale(context, pendingResult = goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        CarWidgetManager.updateWidgetsForStyle(context, appWidgetManager, intArrayOf(appWidgetId), style)
    }
}

/** 4×1 Minimalist Dark Control Bar Widget */
class CarWidgetBarProvider : BaseCarWidgetProvider(CarWidgetStyle.BAR)

/** 2×2 Compact Vehicle Status & Quick Control Widget */
class CarWidgetCompactProvider : BaseCarWidgetProvider(CarWidgetStyle.COMPACT)

/** 4×2 Classic Wide Vehicle Card Widget */
class CarWidgetMediumProvider : BaseCarWidgetProvider(CarWidgetStyle.MEDIUM)

/** 4×3 Flagship Full-Telemetry Showroom Dashboard Widget */
class CarWidgetLargeProvider : BaseCarWidgetProvider(CarWidgetStyle.LARGE)
