import os
import jsons
from flask import Flask, request, jsonify, abort
from models import db, Item
from repository import ItemRepository
from SynCache.Cache import Cache
from sqlalchemy.inspection import inspect

app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///database.db"
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

db.init_app(app)
with app.app_context():
    db.create_all()


# CREATE
@app.route("/items", methods=["POST"])
def create_item():
    data = request.get_json()
    if not data or "name" not in data:
        abort(400, "Name is required")

    item = ItemRepository.create(
        name=data["name"],
        description=data.get("description")
    )
    return jsonify(item.to_dict()), 201


# READ ALL
@app.route("/items", methods=["GET"])
def get_items():
    items = ItemRepository.get_all()
    return jsonify([item.to_dict() for item in items])


# READ ONE
@app.route("/items/<int:item_id>", methods=["GET"])
def get_item(item_id):
    item = ItemRepository.get_by_id(item_id=item_id)
    if not item:
        abort(404, "Item not found")
    return jsonify(item.to_dict())


# UPDATE
@app.route("/items/<int:item_id>", methods=["PUT"])
def update_item(item_id):
    data = request.get_json()
    item = ItemRepository.get_by_id(item_id=item_id)
    if not item:
        abort(404, "Item not found")

    item = ItemRepository.update(
        item=item,
        name=data.get("name"),
        description=data.get("description")
    )
    return jsonify(item.to_dict())


@app.route("/items/<int:item_id>", methods=["DELETE"])
def delete_item(item_id):
    item = ItemRepository.get_by_id(item_id=item_id)
    if not item:
        abort(404, "Item not found")

    ItemRepository.delete(item=item)
    return jsonify({"message": "Item deleted"}), 200


def serialize_sqlalchemy_model(obj, **kwargs):
    return {
        c.key: getattr(obj, c.key)
        for c in inspect(obj).mapper.column_attrs
    }


def deserialize_sqlalchemy_model(data: dict, cls, **kwargs):
    return cls(**data)


if __name__ == "__main__":
    Cache.initialize("wss://broker.syncache.tabariyya.com/",
                     os.environ.get("BROKER_TOKEN"),
                     100)
    # IMPORTANT This is importing so we can be able to Cache All SQLAlchemy models
    jsons.set_serializer(serialize_sqlalchemy_model, db.Model)
    jsons.set_deserializer(deserialize_sqlalchemy_model, db.Model)
    app.run(debug=True, host="0.0.0.0", port=8080)
