package net.ripe.rpki.publicationserver

class Repository {

  def update(msg: QueryPdu) : ReplyPdu = {
    msg match {
      case PublishQ(uri, _,  _) => PublishR(uri)
      case WithdrawQ(uri, _) => WithdrawR(uri)
    }
  }

}
