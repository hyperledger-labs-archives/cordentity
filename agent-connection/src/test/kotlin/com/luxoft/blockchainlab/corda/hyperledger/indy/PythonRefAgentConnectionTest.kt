package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.models.KeyCorrectnessProof
import com.luxoft.blockchainlab.hyperledger.indy.models.TailsResponse
import org.junit.Test
import rx.Single
import java.io.File
import kotlin.test.assertEquals
import java.nio.file.Paths
import java.util.*

class PythonRefAgentConnectionTest {

    class InvitedPartyProcess (
            private val agentUrl: String,
            val proofSchemaId: String = "${Random().nextInt()}:::1",
            val tailsHash: String = "${Random().nextInt(Int.MAX_VALUE)}"
            ) {

        fun start(invitationString: String) {
            val rand = Random().nextInt()
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User$rand", "pass$rand").handle { _, ex ->
                    if (ex != null) {
                        throw AgentConnectionException(ex.message!!)
                    }
                    else acceptInvite(invitationString).subscribe { master ->
                        val tails = master.requestTails(tailsHash).toBlocking().value().tails[tailsHash]
                        if (tails?.toString(Charsets.UTF_8) != tailsHash)
                            throw AgentConnectionException("Tails file content doesn't match!!! hash $tailsHash, received $tails")
                        val offer = CredentialOffer(proofSchemaId, ":::1", KeyCorrectnessProof("", "", emptyList()), "")
                        master.sendCredentialOffer(offer)
                        disconnect()
                    }
                }
            }
        }
    }

    class MasterProcess (
            private val agentUrl: String,
            private val invitedPartyAgents: List<String>) {

        fun start() {
            val rand = Random().nextInt()
            val tailsDir = File("tails").apply { deleteOnExit() }
            if (!tailsDir.exists())
                tailsDir.mkdirs()
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
                val invitedPartiesCompleted = mutableListOf<Single<Boolean>>()
                invitedPartyAgents.forEach { agentUrl ->
                    val party = InvitedPartyProcess(agentUrl)
                    Paths.get("tails", party.tailsHash).toFile().apply { deleteOnExit() }
                        .writeText(party.tailsHash, Charsets.UTF_8)
                    invitedPartiesCompleted.add(Single.create { observer ->
                        generateInvite().subscribe {invitation ->
                            waitForInvitedParty(invitation).subscribe { invitedParty ->
                                invitedParty.handleTailsRequestsWith {
                                    TailsHelper.DefaultReader(tailsDir.absolutePath).read(it)
                                }
                                invitedParty.receiveCredentialOffer().subscribe { proof ->
                                    assertEquals(proof?.schemaIdRaw, party.proofSchemaId)
                                    observer.onSuccess(true)
                                }
                            }
                            party.start(invitation)
                        }
                    })
                }
                Single.zip(invitedPartiesCompleted) {}.toBlocking().value()
                disconnect()
            }
        }
    }

    private val invitedPartyAgents = listOf(
            "ws://127.0.0.1:8094/ws",
            "ws://127.0.0.1:8096/ws",
            "ws://127.0.0.1:8097/ws",
            "ws://127.0.0.1:8098/ws",
            "ws://127.0.0.1:8099/ws"
            )
    private val masterAgent = "ws://127.0.0.1:8095/ws"

    @Test
    fun `externalTest`() = repeat(10) {
        MasterProcess(masterAgent, invitedPartyAgents).apply { start() }
    }

    @Test
    fun `client to server connection is interrupted while exchanging messages`() {
        val tailsHash = "${Random().nextInt(Int.MAX_VALUE)}"
        class Client (private val agentUrl: String) {
            fun connect(invitationString: String) : IndyPartyConnection {
                val rand = Random().nextInt()
                val agentConnection = PythonRefAgentConnection()
                agentConnection.connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
                return agentConnection.acceptInvite(invitationString).toBlocking().value()
            }
        }
        class Server (private val agentUrl: String) {
            fun getInvite() : String {
                val rand = Random().nextInt()
                val agentConnection = PythonRefAgentConnection()
                agentConnection.connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
                val invitationString = agentConnection.generateInvite().toBlocking().value()
                agentConnection.waitForInvitedParty(invitationString).subscribe { invitedParty ->
                    val partyDid = invitedParty.partyDID()
                    println("Server: client $partyDid connected")
                    invitedParty.handleTailsRequestsWith {
                        TailsResponse(tailsHash, mapOf(tailsHash to tailsHash.toByteArray()))
                    }
                }
                return invitationString
            }
        }
        class ExtraClient (private val agentUrl: String) {
            fun connect() {
                val rand = Random().nextInt()
                val agentConnection = PythonRefAgentConnection()
                agentConnection.connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
            }
        }
        val invitationString = Server(masterAgent).getInvite()
        val clientConnection = Client(invitedPartyAgents[0]).connect(invitationString)
        println("Client connected the agent. Local DID is ${clientConnection.myDID()}.")
        repeat(5) {
            val tails = clientConnection.requestTails(tailsHash).toBlocking().value()
            println("Tails received: ${tails.tails[tailsHash]}")
        }
        ExtraClient(invitedPartyAgents[0]).connect()
        val tails = clientConnection.requestTails(tailsHash).toBlocking().value()
        println("Latest tails: ${tails.tails[tailsHash]}")
    }
}
