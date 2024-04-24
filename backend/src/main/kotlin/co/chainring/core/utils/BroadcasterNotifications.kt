package co.chainring.core.utils

import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.core.model.Address

typealias BroadcasterNotifications = MutableMap<Address, MutableList<Publishable>>
fun BroadcasterNotifications.add(address: Address, publishable: Publishable) {
    this.getOrPut(address) { mutableListOf() }.add(publishable)
}
