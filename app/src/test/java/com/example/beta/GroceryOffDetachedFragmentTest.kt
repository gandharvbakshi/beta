package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import com.example.beta.automation.InstructionParser
import org.junit.Assert.*
import org.junit.Test

class GroceryOffDetachedFragmentTest {
    private fun off(source:String)=prepareSwiggyMcpItems(source){null}
    private fun roles(item:ParsedItem)=listOf(item.query,item.quantity,item.strictMatchPhrase,item.retailPackCount,
        item.avoidPhrases,item.cartQuantityMode,item.quantitySignal,item.unprovenIdentityScope)

    @Test fun completeCourtesyDoesNotInventAProductOrChangeEitherPurchasedRole(){
        val base="Two 200 g tubs of plain curd and one loaf of whole wheat bread"
        val original=off(base)
        assertEquals(listOf("plain curd tub 200 g","whole wheat bread loaf"),original.map{it.query})
        assertEquals(listOf(Quantity.Count(2),Quantity.Count(1)),original.map{it.quantity})
        for(tail in listOf(", please.",", kindly!",", .",", !","; ।")){
            val source=base+tail;val result=off(source)
            assertEquals(original.map(::roles),result.map(::roles))
            assertNull(swiggyMcpItemValidationMessage(source,result))
        }
        for(s in listOf(".", " ! ", "please.", "Kindly!", "।"))assertTrue(off(s).isEmpty())
    }

    @Test fun reportedTwoRowsRequireTheRequestedActualTubsAndLoafOnSyntheticShelf(){
        val p=off("Two 200 g tubs of plain curd and one loaf of whole wheat bread, please.")
        fun offer(i:Int,size:String,form:String):SwiggyMcpClient.RecommendationCandidate{
            val title=(if(i==0)"Plain Curd" else "Whole Wheat Bread")+" "+form
            return SwiggyMcpClient.RecommendationCandidate("s$i","$title · $size",variant=size,skuId="v$i",
                catalogueQuantity=CatalogueQuantityEvidence(1,"search_variation","s$i","v$i",
                    CatalogueQuantityFields(packSize=size,quantityDescription="1 $form")))
        }
        fun build(size:String,tub:String,loaf:String):SwiggyCartReviewRequest?{
            val cs=listOf(offer(0,size,tub),offer(1,"400 g",loaf))
            val checked=p.mapIndexed{i,item->assessSwiggyIntentQuantity(item,SwiggyMcpClient.Recommendations(listOf(cs[i])))}
            return SwiggyCartReviewRequest.build(checked,cs.indices.associateWith{cs[it]},emptySet())
        }
        val good=requireNotNull(build("200 g","tub","loaf"))
        assertTrue(good.quantityProposals.isEmpty())
        assertEquals(listOf("v0" to 2,"v1" to 1),good.requestedItems.map{it.skuId to it.quantity})
        assertNull(build("100 g","tub","loaf"));assertNull(build("200 g","pouch","loaf"))
        assertNull(build("200 g","tub","bag"))
    }

    @Test fun ambiguousOrSubjectlessMaterialHoldsTheBasketRatherThanLookingUpPunctuationOrDroppingQuantity(){
        for(fragment in listOf("?","+","±","—","….","...","=","₹","\u0301","५","5",".5","5 g.","ml","kg?",
            "please 5.","kindly ?","two","2:30","5%")){
            val source="milk, $fragment";val items=off(source)
            assertEquals("No invented product for $fragment",listOf("milk"),items.map{it.query})
            assertEquals(Quantity.Default,items.single().quantity)
            assertNotNull("Unresolved original remains held",swiggyMcpItemValidationMessage(source,items))
        }
        val trailingAnd="milk and ."; val items=off(trailingAnd)
        assertTrue(items.isEmpty() || swiggyMcpItemValidationMessage(trailingAnd,items)!=null)
    }

    @Test fun onlyTheRevalidatedProjectedRowCanOwnItsSpecificDetachedRetailSizeForCountAnalysis(){
        val s="one carton coconut milk, 400 ml"
        val q="coconut milk 400 ml carton"
        fun request(raw:String=s,query:String=q,count:Int=1)=ParsedItem(raw,query,Quantity.Count(count),strictMatchPhrase=query)
        val good=InstructionParser.parseSourceCountWitness(s,request())
        assertEquals(1,good.size);assertNull(good.single().quantitySignal)
        // OFF has no projected 400 ml query. Do not shop the cheaper wrong size by ignoring its suffix.
        assertNotNull(swiggyMcpItemValidationMessage(s,off(s)))
        for((text,row) in listOf(s to request(query="coconut milk 500 ml carton"),
            s to request(query="coconut milk 400 ml can"),s to request(count=2),
            s to request(raw="one carton coconut milk"),"milk, 5" to request("milk, 5"),
            "one carton coconut milk, ml" to request("one carton coconut milk, ml"),
            "one carton coconut milk, 100 ml, 400 ml" to request("one carton coconut milk, 100 ml, 400 ml"),
            "one carton coconut milk, 400 ml?" to request("one carton coconut milk, 400 ml?"))){
            assertNotNull(swiggyMcpItemValidationMessage(text,InstructionParser.parseSourceCountWitness(text,row)))
        }
    }

    @Test fun mixedOrConditionalTextNeverQualifiesAsEmptyCourtesyAndNumericNamesStayIntact(){
        val milk=off("milk")
        for(rest in listOf("please no sugar.","if available.","please or rice.","नमक","cafe\u0301")){
            val src="milk, $rest";val r=off(src)
            assertTrue("Not treated as neutral punctuation",r.isEmpty() || r.map(::roles)!=milk.map(::roles) ||
                swiggyMcpItemValidationMessage(src,r)!=null)
        }
        for(s in listOf("one 7 Up","two 5 Star chocolate bars","एक किलो चावल")){
            val plain=off(s); assertTrue(plain.isNotEmpty())
            assertEquals(plain.map(::roles),off("$s, please.").map(::roles))
        }
    }
}
