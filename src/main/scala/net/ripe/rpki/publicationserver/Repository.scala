package net.ripe.rpki.publicationserver

class Repository {

  def update(msg: QueryMsg) : ReplyMsg = {
    new ReplyMsg(msg.pdus.map {
      case PublishQ(uri, _) => PublishR(uri)
      case WithdrawQ(uri) => WithdrawR(uri)
    })
  }

}
