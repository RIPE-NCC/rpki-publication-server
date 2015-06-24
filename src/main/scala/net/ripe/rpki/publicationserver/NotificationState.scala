package net.ripe.rpki.publicationserver

import java.util.concurrent.atomic.AtomicReference

import net.ripe.rpki.publicationserver.model.Notification

class NotificationState {
  private val state: AtomicReference[Notification] = new AtomicReference[Notification]()

  def get = state.get()

  def update(notification: Notification): Unit = state.set(notification)
}
