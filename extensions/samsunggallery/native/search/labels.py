"""The words Search by content can give a photo.

SAMSUNG: Gallery's own scene keys (the ones it translates, CategoryType.VISUAL_SEARCH_SUB_CATEGORY_STRING_MAP, in the
spelling this Gallery uses off One UI), with the parent Gallery files them under: None = a Things category,
"Scenery" = a Scenery category, "documents" = a Documents category, and the words that put a photo in each.

WORDS: everyday words and phrases people search for. A photo gets the ones that fit it best, as search tags.

REQUIRES: phrases a photo keeps only together with a key.
"""

SAMSUNG = [
    # key, parent, the words that put a photo in it[, words one of which must also fit: for breeds that are other
    # things too ("saint bernard", "chow chow")]
    ("Food", None, """food, meal, dish, breakfast, lunch, dinner, brunch, snack, dessert, appetizer, pizza, burger,
        hamburger, cheeseburger, sandwich, sub sandwich, wrap, burrito, taco, quesadilla, nachos, enchilada, kebab,
        gyro, falafel, hummus, pasta, spaghetti, lasagna, ravioli, macaroni and cheese, noodles, ramen, pho, udon,
        pad thai, fried rice, rice, risotto, paella, curry, stew, soup, chili, salad, caesar salad, fruit salad,
        steak, roast beef, pork, ribs, barbecue, grilled meat, roast chicken, fried chicken, chicken wings,
        turkey dinner, ham, bacon, sausage, meatballs, fish and chips, salmon, tuna, seafood, shrimp dish, oysters,
        mussels, sushi, sashimi, dumplings, dim sum, spring rolls, bao, bibimbap, kimchi, korean barbecue, hot pot,
        tempura, bento, poke bowl, eggs, fried egg, scrambled eggs, omelette, pancakes, waffles, french toast,
        cereal, oatmeal, porridge, yogurt, granola, toast, bread, baguette, croissant, bagel, muffin, scone,
        pretzel, cheese, cheese board, charcuterie, french fries, mashed potatoes, baked potato, cake, birthday
        cake, wedding cake, chocolate cake, cheesecake, cupcake, cookies, brownie, pie, apple pie, tart, donut,
        pastry, macarons, ice cream, gelato, pudding, street food, fast food, buffet, picnic food, vegan food,
        healthy food, junk food, pizza slice, pepperoni pizza, fruit, vegetables, fruit basket,
        fruit bowl"""),
    ("Dogs", None, """dog, puppy, golden retriever, labrador, german shepherd, husky, poodle, bulldog, french bulldog,
        beagle, dachshund, chihuahua, pug, corgi, border collie, shih tzu, yorkshire terrier, terrier, schnauzer,
        boxer dog, rottweiler, doberman, great dane, pit bull, spaniel, cocker spaniel, cavalier king charles
        spaniel, maltese, pomeranian, samoyed, shiba inu, akita, dalmatian, greyhound, whippet, sheepdog, bernese
        mountain dog, saint bernard, newfoundland dog, chow chow, jack russell terrier, basset hound, bloodhound,
        mixed breed dog, dog on a leash, dog playing, sleeping dog, dog in the snow, walking the dog, playing with
        a dog""", "dog, puppy"),
    ("Cats", None, """cat, kitten, persian cat, siamese cat, maine coon, ragdoll cat, bengal cat, sphynx cat, british
        shorthair, tabby cat, black cat, white cat, ginger cat, calico cat, tuxedo cat, sleeping cat, cat on a
        sofa""", "cat, kitten"),
    ("Animals", None, """animal, wildlife, farm animals, zoo animals, bird, horse, pony, cow, calf, sheep, lamb, goat,
        pig, chicken, rooster, duck, goose, swan, rabbit, bunny, hamster, squirrel, fox, wolf, bear, polar bear,
        panda, koala, kangaroo, deer, moose, reindeer, lion, tiger, leopard, cheetah, elephant, giraffe, zebra,
        rhinoceros, hippopotamus, camel, llama, alpaca, donkey, monkey, gorilla, chimpanzee, seal, dolphin, whale,
        fish, tropical fish, turtle, frog, lizard, snake, crocodile, butterfly, bee, insect, parrot, pigeon, owl,
        eagle, seagull, flamingo, penguin, peacock, duck pond, horse riding""", "animal, wildlife, bird, farm animals"),
    ("Flower", None, """flower, flowers, rose, tulip, sunflower, daisy, lily, orchid, lavender, poppy, cherry blossom,
        magnolia, hydrangea, peony, dandelion, lotus, iris, carnation, chrysanthemum, hibiscus, marigold, daffodil,
        bouquet, flower arrangement, wildflowers, flower field, flower bed, tulip fields, lavender fields,
        sunflower field, spring blossoms"""),
    ("Cars", None, """car, cars, sports car, race car, vintage car, classic car, electric car, suv, pickup truck,
        taxi, police car, parked car"""),
    ("Bikes", None, """bicycle, bike, mountain bike, road bike, motorcycle, motorbike, scooter, moped, cycling, bmx,
        mountain biking"""),
    ("Boats", None, """boat, sailboat, yacht, ship, cruise ship, ferry, cargo ship, fishing boat, canoe, kayak,
        rowing boat, speedboat, boats in a harbor, harbor with boats"""),
    ("Airplanes", None, """airplane, plane, jet, passenger plane, airliner, private jet, airplane in the sky,
        glider"""),
    ("Trains", None, """train, high speed train, steam train, locomotive, tram, streetcar, subway train"""),
    ("Building", None, """building, skyscraper, skyscrapers, house, apartment building, office building, castle,
        palace, fortress, tower, church, cathedral, mosque, temple, pagoda, monastery, chapel, clock tower,
        cottage, cabin, farmhouse"""),
    ("Shoes", None, """shoes, sneakers, running shoes, boots, high heels, sandals, flip flops, slippers, shoe
        store"""),
    ("Hats", None, """hat, baseball cap, beanie, sun hat, cowboy hat"""),
    ("Bags", None, """bag, handbag, backpack, suitcase, luggage, purse"""),
    ("Eye-glasses", None, """glasses, eyeglasses, sunglasses"""),
    ("Watch", None, """watch, wristwatch, smartwatch"""),
    ("Sport", None, """sport, sports, football, soccer, american football, rugby, basketball, baseball, tennis,
        table tennis, badminton, volleyball, beach volleyball, golf, ice hockey, hockey, cricket, boxing, martial
        arts, karate, swimming, surfing, skiing, snowboarding, ice skating, running, marathon, climbing, rock
        climbing, gymnastics, weightlifting, sports field, football field, tennis court, basketball court, golf
        course, stadium, sports fans, race, skateboarding"""),
    ("Christmas", None, """christmas, christmas tree, christmas lights, christmas presents, christmas dinner, santa
        claus, christmas market, christmas ornaments, gingerbread house, advent calendar, wreath"""),
    ("Wedding", None, """wedding, bride, groom, wedding ceremony, wedding party, wedding dress, wedding cake, wedding
        rings, wedding venue, first dance, bouquet toss, wedding cake cutting"""),
    # Scenery
    ("Coast", "Scenery", """beach, sea, ocean, coast, seaside, waves, shore, island, bay, tropical beach, white sand
        beach, summer beach, beach day, rocky coast, palm beach, kids at the beach, family at the beach, people on a
        beach, sunset over the sea, beach umbrella, footprints in the sand, seashells on the beach, sand castle,
        sunbathing, surf"""),
    ("Mountain", "Scenery", """mountain, mountains, mountain peak, snowy mountains, sunrise over mountains, mountain
        lake, alpine meadow, volcano, glacier"""),
    ("Forest", "Scenery", """forest, woods, jungle, rainforest, bamboo forest, misty forest, autumn forest, winter
        forest, hiking trail"""),
    ("Snow", "Scenery", """snow, snowy landscape, winter landscape, snow-covered trees, snowy street, snowman, frozen
        lake, snowy mountains, winter forest"""),
    ("Sunsets", "Scenery", """sunset, sunrise, dusk, dawn, golden hour, sunset over the sea, sunrise over mountains,
        city at dusk"""),
    ("Night scene", "Scenery", """night sky, stars, milky way, city at night, city lights, skyline at night, street
        at night, road at night, bridge at night, harbor at night, northern lights, aurora, moon, full moon"""),
    ("City overlook", "Scenery", """city skyline, cityscape, downtown, city view, skyline at night, rooftops, view
        from above"""),
    ("Clouds", "Scenery", """clouds, cloudy sky"""),
    ("Deserts", "Scenery", """desert, sand dunes, oasis, desert road, canyon, red rocks"""),
    ("Waterfall", "Scenery", """waterfall, frozen waterfall"""),
    ("Open country", "Scenery", """countryside, field, meadow, farmland, prairie, savanna, plains, rice terraces, tea
        plantation, hills, wheat field, alpine meadow, landscape"""),
    ("Street", "Scenery", """street, road, alley, crosswalk, intersection, old street, cobblestone street, empty
        street, rainy street, snowy street, street at night"""),
    ("Fountains", "Scenery", """fountain"""),
    # Documents
    ("Docs", "documents", """document, documents, page, printed text, form, application form, contract, letter,
        certificate, report, scanned document, photo of a document, credit card, debit card, bank card, payment
        card, id card, identity card, driver's license, driving licence, passport, student id, bank statement,
        tax form, medical report, insurance card"""),
    ("Receipt", "documents", """receipt, shopping receipt, invoice, bill, utility bill, pay slip"""),
    ("Business card", "documents", """business card"""),
    ("QR", "documents", """qr code"""),
    ("Barcode", "documents", """barcode"""),
    ("Map", "documents", """map, city map, subway map, road map, map on a phone"""),
    ("Newspaper", "documents", """newspaper"""),
    ("Signage", "documents", """sign, signboard, street sign, shop sign, neon sign, warning sign, information sign,
        road sign, stop sign"""),
    ("Website", "documents", """website, web page"""),
    ("Whiteboard", "documents", """whiteboard, blackboard, chalkboard, writing on a whiteboard, flip chart"""),
    ("Cartoon", "documents", """cartoon, comic, comic book, manga"""),
]



# Phrases that name a thing in a scene fit the scene alone too ("dog in the snow" for snowy fields): a photo keeps them
# as search words only with the key of the thing.
REQUIRES = {
    "Dogs": """dog on a leash, dog playing, sleeping dog, dog in the snow, dog toy, walking the dog, playing with a dog""",
    "Cats": """sleeping cat, cat on a sofa, cat tree""",
    "Coast": """beach day, beach umbrella, summer beach, tropical beach, palm beach, white sand beach,
        seashells on the beach, kids at the beach, family at the beach, people on a beach, footprints in the sand""",
}

def split(text):
    return [w for w in (" ".join(x.split()) for x in text.replace("\n", " ").split(",")) if w]


WORDS = {
    "animals": """dog, puppy, cat, kitten, pet, animal, bird, horse, pony, cow, calf, bull, sheep, lamb, goat, pig,
        chicken, rooster, duck, goose, swan, turkey, rabbit, bunny, hamster, guinea pig, mouse, rat, squirrel,
        chipmunk, hedgehog, fox, wolf, bear, polar bear, panda, koala, kangaroo, deer, moose, elk, reindeer,
        lion, tiger, leopard, cheetah, jaguar, lynx, elephant, giraffe, zebra, rhinoceros, hippopotamus, camel,
        llama, alpaca, donkey, monkey, gorilla, chimpanzee, orangutan, lemur, sloth, bat, raccoon, skunk, otter,
        beaver, seal, sea lion, walrus, dolphin, whale, shark, fish, goldfish, tropical fish, jellyfish, octopus,
        squid, crab, lobster, shrimp, starfish, seashell, sea turtle, turtle, tortoise, frog, toad, lizard, gecko,
        iguana, chameleon, snake, crocodile, alligator, dinosaur, insect, butterfly, moth, bee, wasp, ant,
        ladybug, beetle, dragonfly, grasshopper, caterpillar, spider, snail, worm, scorpion, parrot, macaw,
        budgie, canary, pigeon, dove, crow, raven, magpie, sparrow, robin, blue jay, cardinal, hummingbird,
        woodpecker, owl, eagle, hawk, falcon, vulture, seagull, pelican, flamingo, heron, stork, crane bird,
        penguin, ostrich, peacock, kingfisher, puffin, wildlife, farm animals, zoo animals, aquarium,
        dog on a leash, dog playing, sleeping dog, sleeping cat, cat on a sofa, dog in the snow, horse riding,
        bird feeder, nest, eggs in a nest, pet bed, dog toy, cat tree, fish tank, birdcage""",
    "dog and cat breeds": """golden retriever, labrador, german shepherd, husky, poodle, bulldog, french bulldog,
        beagle, dachshund, chihuahua, pug, corgi, border collie, shih tzu, yorkshire terrier, terrier, schnauzer,
        boxer dog, rottweiler, doberman, great dane, pit bull, spaniel, cocker spaniel, cavalier king charles
        spaniel, maltese, pomeranian, samoyed, shiba inu, akita, dalmatian, greyhound, whippet, sheepdog,
        bernese mountain dog, saint bernard, newfoundland dog, chow chow, jack russell terrier, basset hound,
        bloodhound, mixed breed dog, persian cat, siamese cat, maine coon, ragdoll cat, bengal cat, sphynx cat,
        british shorthair, tabby cat, black cat, white cat, ginger cat, calico cat, tuxedo cat""",
    "food": """food, meal, dish, breakfast, lunch, dinner, brunch, snack, dessert, appetizer, pizza, burger,
        hamburger, cheeseburger, hot dog, sandwich, sub sandwich, wrap, burrito, taco, quesadilla, nachos,
        enchilada, kebab, gyro, falafel, hummus, pasta, spaghetti, lasagna, ravioli, macaroni and cheese,
        noodles, ramen, pho, udon, pad thai, fried rice, rice, risotto, paella, curry, stew, soup, chili,
        salad, caesar salad, fruit salad, steak, roast beef, pork, ribs, barbecue, grilled meat, roast chicken,
        fried chicken, chicken wings, turkey dinner, ham, bacon, sausage, meatballs, fish and chips, salmon,
        tuna, seafood, shrimp dish, oysters, mussels, sushi, sashimi, dumplings, dim sum, spring rolls, bao,
        bibimbap, kimchi, korean barbecue, hot pot, tempura, bento, poke bowl, eggs, fried egg, scrambled eggs,
        omelette, pancakes, waffles, french toast, cereal, oatmeal, porridge, yogurt, granola, toast, bread,
        baguette, croissant, bagel, muffin, scone, biscuit, pretzel, tortilla, cheese, cheese board,
        charcuterie, olives, french fries, potato chips, mashed potatoes, baked potato, popcorn, nuts, peanuts,
        crackers, cake, birthday cake, wedding cake, chocolate cake, cheesecake, cupcake, cookies, brownie,
        pie, apple pie, tart, donut, pastry, macarons, ice cream, gelato, popsicle, frozen yogurt, pudding,
        chocolate, candy, sweets, lollipop, gummy bears, cotton candy, honey, jam, peanut butter, butter,
        sauce, ketchup, mustard, mayonnaise, spices, herbs, flour, sugar, dough, baking, cooking, grilling,
        barbecue grill, home cooking, street food, fast food, takeout, food truck, buffet, picnic food,
        vegan food, vegetarian food, healthy food, junk food, homemade bread, sourdough, pizza slice,
        pepperoni pizza""",
    "fruit and vegetables": """fruit, vegetables, apple, banana, orange, lemon, lime, grapefruit, mandarin,
        grapes, strawberry, strawberries, blueberries, raspberries, blackberries, cherries, peach, pear, plum,
        apricot, mango, pineapple, papaya, kiwi fruit, watermelon, melon, coconut, pomegranate, fig, dates, avocado,
        tomato, potato, sweet potato, carrot, onion, garlic, ginger, broccoli, cauliflower, cabbage, lettuce,
        spinach, kale, cucumber, zucchini, eggplant, bell pepper, chili pepper, corn, peas, green beans, beans,
        lentils, mushrooms, pumpkin, squash, celery, asparagus, radish, beetroot, artichoke, leek, herbs,
        basil, parsley, mint, rosemary, fruit basket, fruit bowl, vegetable garden, harvest, farmers market""",
    "drinks": """drink, beverage, coffee, cup of coffee, latte, cappuccino, espresso, latte art, iced coffee,
        tea, cup of tea, green tea, bubble tea, matcha, hot chocolate, milk, milkshake, smoothie, juice,
        orange juice, lemonade, water, bottle of water, soda, cola, energy drink, beer, glass of beer,
        draft beer, wine, red wine, white wine, champagne, cocktail, mojito, martini,
        whiskey, vodka, gin, rum, sake, shot glass, bar drinks, coffee shop, coffee beans, teapot, kettle,
        wine glass, beer bottle, wine bottle, mug, thermos, water bottle""",
    "plants and nature": """flower, flowers, rose, tulip, sunflower, daisy, lily, orchid, lavender, poppy,
        cherry blossom, magnolia, hydrangea, peony, dandelion, lotus, iris, carnation, chrysanthemum, hibiscus,
        marigold, daffodil, bouquet, flower arrangement, wildflowers, flower field, flower bed, potted plant,
        houseplant, succulent, cactus, bonsai, fern, moss, ivy, vine, grass, lawn, leaves, autumn leaves,
        fallen leaves, green leaves, tree, trees, palm tree, pine tree, oak tree, maple tree, birch tree,
        willow, christmas tree, tree trunk, branches, roots, bush, hedge, bamboo, seaweed, mushroom in the
        forest, pine cones, acorns, berries on a bush, garden, vegetable patch, greenhouse, orchard,
        vineyard, rice field, wheat field, sunflower field, lavender field, hay bales, rock, stones, pebbles,
        sand, soil, mud, ice, icicles, frost, dew drops, water drops, puddle, rainbow, lightning, storm, fog,
        mist, rain, snowflakes, sunlight, sun rays, shadow""",
    "scenery and places": """beach, sea, ocean, coast, seaside, waves, surf, shore, cliff, island, bay,
        harbor, port, pier, lighthouse, sand dunes, desert, oasis, canyon, valley, hill, hills, mountain,
        mountains, mountain peak, snowy mountains, volcano, glacier, iceberg, cave, rock formation, waterfall,
        river, stream, creek, lake, pond, reservoir, dam, swamp, wetland, marsh, forest, woods, jungle,
        rainforest, bamboo forest, meadow, field, farmland, countryside, prairie, savanna, plains, vineyard
        landscape, landscape, scenery, nature, panorama, sky, blue sky, clouds, cloudy sky, sunset, sunrise,
        dusk, dawn, golden hour, night sky, stars, milky way, moon, full moon, northern lights, aurora,
        eclipse, snow, snowy landscape, winter landscape, autumn landscape, spring, summer, autumn, winter,
        city, city skyline, cityscape, downtown, skyscraper, skyscrapers, city at night, city lights,
        street, road, highway, alley, crosswalk, intersection, traffic, traffic jam, parking lot, gas station,
        bridge, tunnel, railway, train tracks, train station, subway, metro station, bus stop, airport,
        runway, harbor with boats, marina, canal, town, village, small town, old town, neighborhood,
        suburb, house, houses, home, apartment building, cottage, cabin, barn, farm, farmhouse, castle,
        palace, fortress, tower, church, cathedral, mosque, temple, shrine, pagoda, monastery, chapel,
        ruins, ancient ruins, monument, statue, memorial, fountain, square, plaza, market square, park,
        playground, botanical garden, zoo, amusement park, roller coaster, ferris wheel, carousel, theme park,
        stadium, arena, sports field, football field, tennis court, basketball court, golf course,
        swimming pool, ski slope, ski resort, campsite, tent, hiking trail, trail, viewpoint, observation deck,
        landmark, tourist attraction, museum, art gallery, library, school, university, campus, classroom,
        office, office building, factory, warehouse, construction site, crane, hospital, shop, store,
        supermarket, grocery store, shopping mall, market, flea market, street market, bakery, cafe,
        restaurant, bar, pub, nightclub, hotel, hotel room, resort, pool, spa, gym, fitness center, cinema,
        theater, concert hall, stage, wedding venue, garden party, backyard, front yard, porch, balcony,
        terrace, rooftop, patio, garage, driveway, fence, gate, wall, brick wall, graffiti wall, mural,
        staircase, hallway, corridor, elevator, escalator, window, door, doorway, arch, columns, dome,
        lighthouse at night, eiffel tower, big ben, statue of liberty, colosseum, pyramids, great wall""",
    "home and objects": """living room, bedroom, kitchen, bathroom, dining room, home office, kids room, nursery,
        laundry room, basement, attic, closet, interior, room, sofa, couch, armchair, chair, table,
        dining table, coffee table, desk, office chair, bed, pillow, blanket, bunk bed, crib, wardrobe,
        dresser, bookshelf, shelf, cabinet, drawer, rug, carpet, curtains, blinds, lamp, ceiling light,
        chandelier, candle, candles, fireplace, mirror, clock, wall clock, picture frame, painting on a wall,
        poster on a wall, vase, flower vase, houseplant on a shelf, decoration, ornament, cushion, sink,
        bathtub, shower, toilet, towel, toothbrush, toothpaste, soap, shampoo, hair dryer, razor, makeup,
        lipstick, perfume, nail polish, skincare products, cosmetics, comb, hairbrush, refrigerator, fridge,
        freezer, stove, oven, microwave, toaster, kettle on a stove, coffee machine, blender, mixer,
        dishwasher, washing machine, dryer, vacuum cleaner, iron, fan, air conditioner, heater, radiator,
        pots and pans, frying pan, pot, plate, plates, bowl, cup, glass, fork, knife, spoon, chopsticks,
        cutting board, kitchen knife, baking tray, jar, bottle, can, box, cardboard box, package, parcel,
        delivery box, shopping bag, plastic bag, basket, laundry basket, trash can, recycling bin, bucket,
        broom, mop, sponge, ladder, toolbox, light bulb, battery, charger, cable, power outlet, extension
        cord, remote control, keys, key, lock, padlock, wallet, purse, coins, money, cash, banknotes,
        piggy bank, umbrella, flashlight, candle holder, gift, present, gift box, wrapping paper, balloons,
        party decorations, flag, sticker, magnet, souvenir, trophy, medal, award, certificate""",
    "electronics and screens": """phone, smartphone, mobile phone, iphone, cell phone, tablet, laptop, computer,
        desktop computer, monitor, computer screen, keyboard, computer mouse, mouse pad, headphones, earbuds,
        earphones, speaker, smart speaker, microphone, camera, digital camera, film camera, camera lens,
        tripod, drone, television, tv, tv screen, game console, video game, gaming controller, joystick,
        virtual reality headset, smartwatch, fitness tracker, e-reader, printer, scanner, router, hard drive,
        usb stick, memory card, sd card, circuit board, electronics, robot, calculator, projector, cables and
        wires, screenshot, phone screen, screen with text, code on a screen, video call, chat messages, text
        messages, social media post, app screen, website, web page, game screenshot, error message, settings
        screen, home screen, lock screen, map on a phone""",
    "clothing and fashion": """fashion, clothes, clothing, outfit, dress, wedding dress, evening dress, skirt,
        shirt, t-shirt, blouse, sweater, hoodie, jacket, leather jacket, denim jacket, coat, winter coat,
        raincoat, suit, tuxedo, tie, bow tie, jeans, trousers, pants, shorts, leggings, swimsuit, bikini,
        swimwear, pajamas, underwear, socks, uniform, costume, halloween costume, kimono, sari, scarf, gloves,
        mittens, belt, hat, cap, baseball cap, beanie, sun hat, cowboy hat, helmet, shoes, sneakers, running
        shoes, boots, high heels, sandals, flip flops, slippers, bag, handbag, backpack, suitcase, luggage,
        wallet in a hand, jewelry, necklace, ring, wedding ring, engagement ring, earrings, bracelet, watch,
        wristwatch, sunglasses, glasses, eyeglasses, hair, hairstyle, braids, beard, tattoo, makeup look,
        nails, manicure, clothing store, shoe store, closet full of clothes, folded clothes, knitting, yarn,
        sewing, fabric""",
    "vehicles": """car, cars, vehicle, sports car, race car, vintage car, classic car, electric car, suv, pickup
        truck, truck, lorry, van, minivan, bus, school bus, double decker bus, taxi, police car, ambulance,
        fire truck, tow truck, tractor, excavator, bulldozer, crane truck, forklift, motorcycle, motorbike,
        scooter, moped, electric scooter, bicycle, bike, mountain bike, road bike, tricycle, unicycle,
        skateboard, roller skates, train, high speed train, steam train, locomotive, tram, streetcar,
        subway train, cable car, gondola, boat, sailboat, yacht, ship, cruise ship, ferry, cargo ship,
        fishing boat, canoe, kayak, rowing boat, speedboat, jet ski, submarine, airplane, plane, jet,
        passenger plane, airliner, private jet, helicopter, hot air balloon, glider, rocket, space shuttle,
        car interior, dashboard, steering wheel, car seat, tire, wheel, engine, license plate, car crash,
        parked car, car wash, garage with cars, traffic lights, road sign, stop sign, parking meter""",
    "sports and activities": """sport, sports, football, soccer, soccer ball, american football, rugby,
        basketball, baseball, softball, tennis, table tennis, badminton, volleyball, beach volleyball, golf,
        hockey, ice hockey, cricket, boxing, martial arts, karate, judo, wrestling, fencing, archery,
        swimming, diving, snorkeling, scuba diving, surfing, windsurfing, kitesurfing, sailing, rowing,
        kayaking, fishing, skiing, snowboarding, ice skating, figure skating, sledding, snowman, snowball fight,
        running, marathon, jogging, cycling, mountain biking, bmx, skateboarding, climbing, rock climbing,
        bouldering, hiking, trekking, camping, backpacking, horse riding, equestrian, gymnastics, yoga,
        pilates, workout, weightlifting, fitness, gym workout, dancing, ballet, dance performance, bowling,
        billiards, darts, chess, board games, card games, playing cards, puzzle, jigsaw puzzle, video gaming,
        playing guitar, playing piano, singing, karaoke, painting a picture, drawing, reading, reading a book,
        writing, studying, working on a laptop, cooking in a kitchen, baking cookies, gardening, picnic,
        barbecue party, shopping, sightseeing, traveling, road trip, beach day, sunbathing, meditation,
        sleeping, walking the dog, playing with a dog, feeding birds, birdwatching, stargazing, fireworks
        show, team photo, trophy celebration, race, competition, match, game day, sports fans, cheerleaders,
        referee, goal, ball, tennis racket, golf club, baseball bat, skis, surfboard, bicycle helmet, dumbbells,
        treadmill, yoga mat, swimming goggles, life jacket""",
    "people and events": """person, people, man, woman, child, children, kid, kids, baby, toddler, teenager,
        boy, girl, family, family photo, friends, group photo, couple, crowd, audience, portrait, selfie,
        group selfie, face, smile, smiling, laughing, hug, kiss, holding hands, mother and baby, father and son,
        grandparents, old man, old woman, students, graduation, graduation ceremony, wedding, bride, groom,
        wedding ceremony, wedding party, birthday, birthday party, birthday candles, party, celebration,
        anniversary, baby shower, christmas, christmas dinner, christmas lights, christmas presents, santa
        claus, new year, new year's eve, fireworks, halloween, pumpkin carving, jack o lantern, easter,
        easter eggs, thanksgiving, valentine's day, mother's day, father's day, hanukkah, diwali, eid,
        chinese new year, lunar new year, carnival, festival, music festival, concert, live music, band,
        dj, nightlife, parade, protest, ceremony, conference, meeting, presentation, lecture, classroom
        lesson, office work, teamwork, job interview, business meeting, dinner party, family dinner,
        restaurant dinner, date night, holiday, vacation, trip, travel, journey, tourist, sightseeing tour,
        school, first day of school, sports day, prom, reunion, funeral, baptism, religious ceremony,
        volunteer, charity event, market day, street performer, artist, musician, chef, doctor, nurse,
        police officer, firefighter, soldier, worker, farmer, athlete, model, dancer, singer, actor""",
    "documents and text": """document, documents, paper, sheet of paper, page, printed text, text, handwriting,
        handwritten note, note, notes, notebook, notepad, sticky notes, letter, envelope, postcard,
        greeting card, birthday card, invitation, wedding invitation, form, application form, contract,
        agreement, certificate, diploma, report, report card, homework, worksheet, exam, test paper, textbook,
        book, open book, books, bookshelf with books, magazine, newspaper, comic, comic book, manga, cartoon,
        drawing, sketch, doodle, diagram, chart, graph, table of numbers, spreadsheet, slide, presentation
        slide, whiteboard, blackboard, chalkboard, flip chart, map, city map, subway map, floor plan,
        blueprint, menu, restaurant menu, recipe, recipe card, shopping list, to-do list, calendar, schedule,
        timetable, ticket, train ticket, boarding pass, plane ticket, concert ticket, movie ticket, receipt,
        shopping receipt, invoice, bill, utility bill, bank statement, check, cheque, credit card, debit card,
        bank card, payment card, gift card, loyalty card, membership card, id card, identity card, driver's
        license, driving licence, passport, passport photo, visa, student id, business card, name tag, badge,
        label, price tag, barcode, qr code, coupon, voucher, flyer, brochure, leaflet, poster, banner, sign,
        signboard, street sign, shop sign, neon sign, warning sign, information sign, road map, prescription,
        medical report, x-ray, lab results, insurance card, vaccination card, tax form, pay slip, warranty,
        manual, instruction manual, product label, nutrition label, ingredients list, wine label, package
        label, shipping label, sheet music, music notes, crossword, sudoku, scoreboard, calendar page,
        writing on a whiteboard, post-it notes, screenshot of text, scanned document, photo of a document,
        id photo, signature, stamp, postage stamp, seal, logo, brand logo""",
    "art and hobbies": """art, artwork, painting, oil painting, watercolor, abstract art, portrait painting,
        landscape painting, drawing, pencil drawing, sketchbook, illustration, digital art, graffiti, street art,
        mural, sculpture, statue, pottery, ceramics, vase art, mosaic, stained glass, tapestry, embroidery,
        quilt, origami, paper craft, crafts, diy project, knitting project, lego, lego bricks, toy, toys,
        teddy bear, stuffed animal, doll, action figure, toy car, model train, puppet, kite, balloon, board
        game, chess board, dice, playing cards, puzzle pieces, musical instrument, guitar, electric guitar,
        acoustic guitar, bass guitar, piano, keyboard instrument, violin, cello, double bass, harp, drums,
        drum kit, trumpet, saxophone, flute, clarinet, trombone, accordion, harmonica, ukulele, banjo,
        vinyl record, record player, cassette, cd, music studio, photography, camera on a tripod, film
        photography, black and white photo, vintage photo, old photograph, collage, photo album, frame""",
    "tools and work": """tool, tools, hammer, screwdriver, wrench, pliers, saw, drill, power drill, nails,
        screws, tape measure, level, paintbrush, paint, paint roller, paint cans, ladder, wheelbarrow, shovel,
        rake, garden hose, lawn mower, chainsaw, axe, workbench, workshop, sewing machine, scissors, needle and
        thread, glue, tape, measuring tape, rope, chain, pipe, wires, construction, construction worker,
        building site, scaffolding, bricks, concrete, wood, planks, logs, firewood, metal, welding, machine,
        machinery, engine parts, gears, laboratory, microscope, test tubes, chemistry, science experiment,
        medical equipment, stethoscope, syringe, pills, medicine, pill bottle, first aid kit, bandage,
        thermometer, wheelchair, crutches, glasses on a table, office supplies, pen, pencil, pens, marker,
        highlighter, eraser, ruler, stapler, paper clips, folder, binder, file folder, desk with a computer,
        workspace, home office desk, notebook and pen, calculator on a desk, envelope and stamps""",
    "misc": """light, lights, string lights, fairy lights, neon lights, lantern, fire, campfire, bonfire, smoke,
        candle light, sparkler, reflection, silhouette, shadow on a wall, pattern, texture, colorful, black
        and white, close-up, macro, aerial view, view from a plane, view from a window, view from above,
        underwater, sky with birds, bird flying, airplane in the sky, contrail, satellite dish, wind turbine,
        windmill, solar panels, power lines, electricity pylon, flag pole, clock tower, bell, mailbox,
        phone booth, bench, park bench, picnic table, swing, slide, sandbox, trampoline, pool float,
        beach umbrella, deckchair, hammock, tent at night, sleeping bag, campervan, caravan, trailer,
        motorhome, map and compass, compass, binoculars, globe, telescope, hourglass, money and coins,
        shopping cart, cash register, vending machine, atm, crowd at a concert, queue, line of people,
        empty street, rainy street, wet road, snowy street, autumn park, spring blossoms, summer beach,
        winter forest, frozen lake, ice skating rink, christmas market, night market, food market,
        flower shop, bookstore, toy store, pet store, vet clinic, dentist, doctor's office, hospital room,
        hotel lobby, airport terminal, airplane window, airplane seat, train interior, bus interior, car trip,
        road at night, city at dusk, sunset over the sea, sunrise over mountains, mountain lake, alpine
        meadow, rocky coast, tropical beach, palm beach, white sand beach, coral reef, desert road,
        lavender fields, tulip fields, rice terraces, tea plantation, autumn forest, misty forest,
        snow-covered trees, frozen waterfall, hot spring, geyser, red rocks, sand castle,
        footprints in the sand, seashells on the beach, boats in a harbor, fishing village, old street,
        cobblestone street, colorful houses, street lamp, street at night, rooftops, city view, skyline at
        night, bridge at night, harbor at night, carnival rides, festival lights, lanterns festival,
        snow globe, gingerbread house, advent calendar, christmas ornaments, wreath, easter bunny,
        pumpkin patch, haunted house, costume party, birthday balloons, confetti, champagne toast,
        wedding rings, wedding cake cutting, first dance, bouquet toss, baby photo, newborn baby, baby
        sleeping, kids playing, children playing in the park, kids at the beach, family at the beach,
        family on holiday, friends at a party, friends at dinner, people dancing, people on a beach,
        people hiking, people skiing, crowd in a stadium"""
}


def words():
    """Every word, once: WORDS, then any key word WORDS lacks."""
    seen, out = set(), []
    for text in list(WORDS.values()) + [k[2] for k in SAMSUNG]:
        for w in split(text):
            if w not in seen:
                seen.add(w)
                out.append(w)
    return out
