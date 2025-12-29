from SynCache.Cache import Cache

from models import db, Item
from SynCache.decorators import cacheable, cache_evict, cache_put


# ONE WAY OF USING THE CACHE
class ItemRepository:

    @staticmethod
    @cache_put("Item", "#result.id")
    def create(name, description=None):
        print("HitDB in create")
        item = Item(name=name, description=description)
        db.session.add(item)
        db.session.commit()
        return item

    @staticmethod
    def get_all():
        return Item.query.all()

    @staticmethod
    @cacheable("Item", "#item_id", return_type=Item)
    def get_by_id(item_id):
        print("HitDB in get")
        return Item.query.get(item_id)

    @staticmethod
    @cache_put("Item", "#item.id")
    def update(item, name=None, description=None):
        print("HitDB in update")
        if name is not None:
            item.name = name
        if description is not None:
            item.description = description
        db.session.commit()
        return item

    @staticmethod
    @cache_evict("Item", "#item.id")
    def delete(item):
        print("HitDB in delete")
        db.session.delete(item)
        db.session.commit()
