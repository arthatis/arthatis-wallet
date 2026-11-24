package com.arthatis.wallet

import android.content.Context
import com.arthatis.wallet.ai.ChatEngine
import com.arthatis.wallet.bareng.Bareng
import com.arthatis.wallet.bareng.MemberProfile
import com.arthatis.wallet.data.Prefs
import com.arthatis.wallet.data.RatesRepo
import com.arthatis.wallet.data.Repository
import com.arthatis.wallet.data.WalletDatabase
import com.arthatis.wallet.net.Http
import com.arthatis.wallet.sync.Vault

/** Manual wiring; the app is small enough that a DI framework would only add weight. */
class AppGraph(context: Context) {
    val prefs = Prefs(context)
    val db = WalletDatabase.open(context)
    val repo = Repository(db.dao())
    val http = Http({ prefs.serverUrl }, { prefs.token })
    val vault = Vault(prefs, db.dao(), http)
    val bareng = Bareng(prefs, db.dao(), http) { MemberProfile(repo.snapshot().profile?.name ?: "—") }
    val rates = RatesRepo(prefs)
    val chat = ChatEngine(prefs, http)
    val emailDrafts = com.arthatis.wallet.email.EmailDraftStore(context)
}
