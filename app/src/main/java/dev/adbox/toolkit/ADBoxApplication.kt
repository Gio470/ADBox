package dev.adbox.toolkit

import android.app.Application

/** Application-owned sessions survive closing/reopening the activity. */
class ADBoxApplication:Application(){val model:ADBoxViewModel by lazy{ADBoxViewModel(this)}}
