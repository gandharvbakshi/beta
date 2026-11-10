package com.example.beta

import java.math.BigDecimal
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class GroceryIntentEditHintsTest {
    private val original="brown bread, two small milk cartons, fresh peas"
    private val phrase="two small milk cartons"
    private fun row(source:String)=GroceryIntentItem("groceries",BigDecimal.ONE,"count",null,"unspecified",
        emptyList(),emptyList(),source,false)
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun range(start:Int,end:Int)="""{"startUtf16":$start,"endUtf16":$end}"""
    private fun range(s:String,p:String)=range(s.indexOf(p),s.indexOf(p)+p.length)
    private fun envelope(s:String,ranges:String)="""{"detail":{"accepted":false,"reason":"intent_needs_review","editHint":{"version":1,"instructionSha256":"${sha(s)}","ranges":[$ranges]}}}"""
    private fun quote(s:String,p:String)=GroceryIntentReviewV2Quote(p,s.indexOf(p),s.indexOf(p)+p.length)
    private fun rejected(s:String,body:String)=assertEquals("Malformed or ambiguous hints must not partially survive",
        emptyList<GroceryIntentReviewV2Quote>(),GroceryIntentEditHints.fromRejection(s,body))

    @Test fun rowWitnessRequiresWholeExactPrimaryAndCannotBeTakenFromARepeatedNeighbour() {
        val rows=listOf(row("brown bread"),row(phrase),row("fresh peas"))
        val expected=quote(original,phrase)
        assertEquals(listOf(expected),GroceryIntentEditHints.fromLocal(original,rows,listOf(phrase)))
        for (affected in listOf(listOf("small milk"),listOf("milk cartons"),listOf("wrong source"),
            listOf(phrase,"wrong source"),listOf(original),emptyList())) {
            assertTrue(GroceryIntentEditHints.fromLocal(original,rows,affected).isEmpty())
        }
        val repeated="$phrase, $phrase, peas"
        assertTrue(GroceryIntentEditHints.fromLocal(repeated,listOf(row(phrase),row("peas")),listOf(phrase)).isEmpty())
        assertTrue(GroceryIntentEditHints.fromLocal(original,rows+row(phrase),listOf(phrase)).isEmpty())
    }

    @Test fun validRangesUseUtf16AndPreserveTheExactOriginalGraphemes() {
        val s="🛒 bread, आधा किलो दही, fresh peas"
        val p="आधा किलो दही"
        assertEquals(listOf(quote(s,p)),GroceryIntentEditHints.fromRejection(s,envelope(s,range(s,p))))
        val b=envelope(s,range(s,p))
        rejected(s.replace("bread","toast"),b) // same UTF-16 length isn't request identity
        val shifted=s.codePointCount(0,s.indexOf(p))
        rejected(s,envelope(s,range(shifted,shifted+p.length))) // Python code-points, not UTF-16
        rejected(s,envelope(s,range(1,2))) // split surrogate
        val combining="tea, e\u0301clair, bread"
        val combiningStart=combining.indexOf("e\u0301clair")
        rejected(combining,envelope(combining,range(combiningStart,combiningStart+1)))
        rejected(combining,envelope(combining,range(combiningStart+1,combiningStart+2)))
        val multi="first mango, brown bread, three peppers, loose rice"
        val three=listOf("first mango","brown bread","loose rice")
        assertEquals(three.map { quote(multi,it) },GroceryIntentEditHints.fromRejection(multi,
            envelope(multi,three.joinToString(",") { range(multi,it) })))
    }

    @Test fun malformedEnvelopesRangesAndDigestsFailClosedAsOneSet() {
        val atom=range(original,phrase)
        val good=envelope(original,atom)
        assertEquals(listOf(quote(original,phrase)),GroceryIntentEditHints.fromRejection(original,good))
        val start=original.indexOf(phrase)
        val numeric="\"startUtf16\":$start"
        val invalid=listOf(
            "", "[]", "{}", good.substringBeforeLast('}'), good+"false",
            good.replace("intent_needs_review","provider_unavailable"),good.replace("false","true"),
            good.replace("\"detail\":","\"unrelated\":true,\"detail\":"),good.replace("\"accepted\":","\"trace\":1,\"accepted\":"),
            good.replace("\"version\":1","\"version\":1,\"version\":1"),
            good.replace("\"ranges\":[","\"rawModelText\":\"never display\",\"ranges\":["),
            good.replace("\"version\":1","\"version\":2"),good.replace(sha(original),"0".repeat(64)),
            good.replace(sha(original),sha(original).drop(1)),good.replace(atom,"[${atom}]"),
            good.replace(numeric,"$numeric,$numeric"),good.replace(numeric,"\"startUtf16\":\"$start\""),
            good.replace(numeric,"\"startUtf16\":$start.0"),good.replace(numeric,"\"startUtf16\":1e1"),
            good.replace(numeric,"\"startUtf16\":-1"),good.replace(numeric,"\"startUtf16\":9999999999999"),
            good.replace(atom,range(start+2,start+1)),good.replace(atom,range(start,start)),good.replace(atom,range(start,original.length+1)),
            good.replace(atom,range(0,original.length)),good.replace(atom,"$atom,$atom"),
            good.replace(atom,"$atom,${range(start+1,start+4)}"),good.replace(atom,"$atom,${range(original,"brown bread")},$atom,${range(original,"fresh peas")}"),
            good.replace(atom,"""{"startUtf16":$start,"endUtf16":${start+phrase.length},"label":"trust me"}""")
        )
        invalid.forEach { rejected(original,it) }
        val repeat="bread, two milk cartons, two milk cartons"
        rejected(repeat,envelope(repeat,range(repeat,"two milk cartons")))
        val long="x".repeat(321);val many="bread, $long, rice"
        rejected(many,envelope(many,range(many,long)))
    }

    @Test fun digestAloneCannotMakeWordFragmentsNumbersOrCroppedQuotedRowsEditable() {
        val s="bread, two 1.5 litre milk cartons, tea"
        for (partial in listOf("mil","1","5 litre","1.5 litre milk","milk cartons")) {
            rejected(s,envelope(s,range(s,partial)))
        }
        assertEquals(listOf(quote(s,"two 1.5 litre milk cartons")),GroceryIntentEditHints.fromRejection(s,
            envelope(s,range(s,"two 1.5 litre milk cartons"))))
    }
}
